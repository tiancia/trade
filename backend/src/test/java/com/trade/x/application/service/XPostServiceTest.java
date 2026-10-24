package com.trade.x.application.service;

import com.trade.client.x.XApiException;
import com.trade.x.application.port.*;
import com.trade.x.domain.exception.XPublishingException;
import com.trade.x.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XPostServiceTest {
    private final Instant now = Instant.parse("2026-10-03T00:00:00Z");
    private final XPostRepository repository = mock(XPostRepository.class);
    private final XDraftGenerator generator = mock(XDraftGenerator.class);
    private final XHumanReviewGateway reviews = mock(XHumanReviewGateway.class);
    private final XPostPublisher publisher = mock(XPostPublisher.class);
    private final AtomicReference<XPost> state = new AtomicReference<>();
    private final XContentPolicy content = new XContentPolicy("开发经验", "简体中文", "平实", "无标签", 5, 120);
    private final XWorkflowPolicy policy = new XWorkflowPolicy(true, true, true, "123", content, 5, 3,
            Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5));
    private XPostService service;

    @BeforeEach void setup() {
        service = service(policy, now);
        when(repository.reserveGeneration(any(), any(), anyInt())).thenAnswer(invocation -> {
            state.set(invocation.getArgument(0)); return true;
        });
        when(repository.save(any(), anyLong())).thenAnswer(this::save);
        when(repository.claimPublishing(any(), anyLong(), any(), anyInt(), any())).thenAnswer(this::save);
        when(repository.finishPublishing(any(), anyLong())).thenAnswer(this::save);
        when(repository.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(state.get())
                .filter(post -> post.id().equals(invocation.getArgument(0))));
        when(repository.actionable(anyInt())).thenAnswer(invocation -> {
            XPost post = state.get();
            return post != null && Set.of(XPostStatus.GENERATING, XPostStatus.PENDING_REVIEW,
                    XPostStatus.APPROVED, XPostStatus.PUBLISHING).contains(post.status()) ? List.of(post) : List.of();
        });
        when(generator.generate(eq(content), anyList())).thenReturn(new GeneratedXPost("把复杂问题拆成可验证的小步骤。", "一般方法，无实时事实"));
        when(publisher.credentialsAvailable("123")).thenReturn(true);
        when(publisher.publish(any())).thenReturn("999");
    }

    @Test void reserveBeforeAiAndReviewNeverPublishesByItself() {
        XPost pending = service.generate().orElseThrow();
        assertEquals(XPostStatus.PENDING_REVIEW, pending.status());
        var order = inOrder(repository, generator, reviews);
        order.verify(repository).reserveGeneration(any(), eq(now), eq(5));
        order.verify(repository).recent(30);
        order.verify(generator).generate(eq(content), eq(List.of()));
        order.verify(repository).save(eq(pending), eq(0L));
        order.verify(reviews).submit(argThat(request -> request.content().equals(pending.body())
                && request.targetUserId().equals("123") && request.version() == pending.contentVersion()
                && request.context().contains("开发经验")
                && request.context().contains("自由文本审核")
                && request.context().contains("冻结格式：FREE_TEXT")
                && !request.context().contains("短对话审核")
                && request.context().contains("不冒充心理或性健康专家")
                && request.context().contains("不保证涨粉或收益")));
        verify(publisher, never()).publish(any());
    }

    @Test void scheduledAiAndReviewFailuresAreReportedWithoutLosingPersistedState() {
        when(generator.generate(eq(content), anyList())).thenThrow(new IllegalStateException("AI quota exhausted"));
        assertFalse(service.runGeneration());
        assertEquals(XPostStatus.GENERATION_FAILED, state.get().status());

        when(generator.generate(eq(content), anyList())).thenReturn(new GeneratedXPost("把复杂问题拆成可验证的小步骤。", "一般方法"));
        when(reviews.submit(any())).thenReturn(false);
        assertFalse(service.runGeneration());
        assertEquals(XPostStatus.PENDING_REVIEW, state.get().status());
        assertFalse(service.runPublishing());

        when(reviews.disabled()).thenReturn(true);
        assertTrue(service.runPublishing());
        verify(publisher, never()).publish(any());
    }

    @Test void scheduledReservationSkipDoesNotReportFailureOrCallAi() {
        when(repository.reserveGeneration(any(), any(), anyInt())).thenReturn(false);
        assertTrue(service.runGeneration());
        verifyNoInteractions(generator, reviews, publisher);
    }

    @Test void dialogueReviewKeepsQuotedLinesAndCreativeNoteSeparate() {
        var dialogueContent = new XContentPolicy("成年人的暧昧", "简体中文", "自然", "仅短对话", 5, 120,
                XContentPolicy.Format.CHAT_DIALOGUE);
        var dialoguePolicy = new XWorkflowPolicy(true, true, true, "123", dialogueContent, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        String body = "“你还在等他？”\n“没有，我只是还没关灯。”";
        String note = "AI 虚构对话；两名成年人，情绪来自否认与等待的反差。";
        when(generator.generate(eq(dialogueContent), anyList())).thenReturn(new GeneratedXPost(body, note));

        XPost pending = service(dialoguePolicy, now).generate().orElseThrow();

        assertEquals(body, pending.body());
        assertEquals(note, pending.reviewNote());
        verify(reviews).submit(argThat(request -> request.content().equals(body)
                && !request.content().contains(note)
                && request.context().contains(note)
                && request.context().contains("冻结格式：CHAT_DIALOGUE")
                && request.context().contains("短对话审核")
                && request.context().contains("潜台词")
                && request.context().contains("不得冒充真实聊天截图、亲历或读者投稿")
                && request.context().contains("创作说明仅用于审核，不随正文发布")));
        verify(publisher, never()).publish(any());
    }

    @Test void invalidDialogueRevisionIsRejectedBeforeSavingOrSubmittingReview() {
        var dialogueContent = new XContentPolicy("成年人的暧昧", "简体中文", "自然", "仅短对话", 5, 120,
                XContentPolicy.Format.CHAT_DIALOGUE);
        var dialoguePolicy = new XWorkflowPolicy(true, true, true, "123", dialogueContent, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        var dialogueService = service(dialoguePolicy, now);
        when(generator.generate(eq(dialogueContent), anyList()))
                .thenReturn(new GeneratedXPost("\"还没睡？\"\n\"在等你的晚安。\"", "AI 虚构对话。"));
        XPost pending = dialogueService.generate().orElseThrow();
        clearInvocations(repository, reviews, publisher);

        assertThrows(IllegalArgumentException.class, () -> dialogueService.revise(pending.id(), pending.revision(),
                "我以为自己已经不再等他的消息了。"));

        assertSame(pending, state.get());
        verify(repository, never()).save(any(), anyLong());
        verifyNoInteractions(reviews, publisher);
    }

    @Test void historyIsAccountScopedAndBoundedBeforeItReachesAi() {
        List<XPost> recent = new ArrayList<>();
        var otherPolicy = new XWorkflowPolicy(true, true, true, "999", content, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        recent.add(XPost.generating("999", "other-account", now, otherPolicy)
                .generated(new GeneratedXPost("其他账号的正文不要传入。", "note"), now));
        for (int i = 0; i < 12; i++) {
            recent.add(XPost.generating("123", "old-" + i, now, policy)
                    .generated(new GeneratedXPost("同账号历史正文编号" + i, "note"), now));
        }
        when(repository.recent(30)).thenReturn(recent);

        service.generate();

        verify(generator).generate(eq(content), argThat(bodies -> bodies.size() == 8
                && bodies.getFirst().equals("同账号历史正文编号0")
                && bodies.getLast().equals("同账号历史正文编号7")));
    }

    @Test void repeatedGeneratorOutputFailsWithoutReviewOrPublication() {
        XPost previous = XPost.generating("123", "previous", now, policy)
                .generated(new GeneratedXPost("把复杂问题拆成可验证的小步骤！", "note"), now);
        when(repository.recent(30)).thenReturn(List.of(previous));

        assertEquals(XPostStatus.GENERATION_FAILED, service.generate().orElseThrow().status());

        verify(generator, times(1)).generate(eq(content), anyList());
        verifyNoInteractions(reviews);
        verify(publisher, never()).publish(any());
    }

    @Test void dailyGenerationReservationStopsPaidAi() {
        when(repository.reserveGeneration(any(), any(), anyInt())).thenReturn(false);
        assertTrue(service.generate().isEmpty());
        verifyNoInteractions(generator, reviews);
    }

    @Test void invalidAiLengthIsFailedWithoutTruncationOrReview() {
        when(generator.generate(eq(content), anyList())).thenReturn(new GeneratedXPost("中".repeat(150), "note"));
        assertEquals(XPostStatus.GENERATION_FAILED, service.generate().orElseThrow().status());
        verifyNoInteractions(reviews);
        verify(publisher, never()).publish(any());
    }

    @Test void scheduleUsesSameDurableIntervalKeyAcrossInstances() {
        List<String> keys = new ArrayList<>();
        when(repository.reserveGeneration(any(), any(), anyInt())).thenAnswer(invocation -> {
            XPost post = invocation.getArgument(0); keys.add(post.generationKey()); return false;
        });
        service.runGeneration(); service(policy, now.plusSeconds(1)).runGeneration();
        assertEquals(2, keys.size()); assertEquals(keys.getFirst(), keys.getLast());
        assertTrue(keys.getFirst().startsWith("scheduled:"));
        verifyNoInteractions(generator);
    }

    @Test void approvalImmediatelyPublishesExactlyReviewedTextAndDuplicateNeverResends() {
        XPost pending = service.generate().orElseThrow();
        XReviewDecision decision = decision(pending, true);
        callback(decision);
        service.runReviews();
        assertEquals(XPostStatus.PUBLISHED, state.get().status());
        assertEquals("999", state.get().postId());
        verify(publisher).publish(argThat(post -> post.status() == XPostStatus.PUBLISHING
                && post.body().equals(pending.body()) && post.contentVersion() == pending.contentVersion()));
        var order = inOrder(repository, publisher);
        order.verify(repository).claimPublishing(any(), eq(2L), eq(now), eq(3), eq(Duration.ofMinutes(30)));
        order.verify(publisher).publish(any());
        order.verify(repository).finishPublishing(any(), eq(3L));
        service.runReviews(); service.runPublishing();
        verify(publisher, times(1)).publish(any());
    }

    @Test void rejectionNeverCallsPublisher() {
        XPost pending = service.generate().orElseThrow(); callback(decision(pending, false));
        service.runReviews(); service.runPublishing();
        assertEquals(XPostStatus.REJECTED, state.get().status()); verify(publisher, never()).publish(any());
    }

    @Test void editingClearsApprovalAndOldButtonsCannotApproveNewBody() {
        XPost pending = service.generate().orElseThrow();
        XReviewDecision original = decision(pending, true);
        XPost approved = service.applyReview(original);
        XPost revised = service.revise(approved.id(), approved.revision(), "修改后正文仍需由人工重新审核。");
        assertEquals(2, revised.contentVersion()); assertNull(revised.reviewedAt());
        verify(reviews).submit(argThat(request -> request.version() == 2
                && request.content().equals(revised.body())
                && request.context().contains("原始 AI 说明不代表当前版本")));
        callback(original); service.runReviews();
        assertEquals(XPostStatus.PENDING_REVIEW, state.get().status());
        verify(publisher, never()).publish(any());
    }

    @Test void frozenContentRulesSurviveConfigurationChange() {
        XPost pending = service.generate().orElseThrow();
        var changedContent = new XContentPolicy("新的方向", "English", "formal", "", 100, 120);
        var changedPolicy = new XWorkflowPolicy(true, true, true, "123", changedContent, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        var changedService = service(changedPolicy, now);
        XPost revised = changedService.revise(pending.id(), pending.revision(), "短正文遵守原有规则。");
        assertEquals(content, revised.contentPolicy());
        verify(reviews).submit(argThat(request -> request.version() == 2
                && request.context().contains("内容方向：开发经验")
                && request.context().contains("语言：简体中文")
                && !request.context().contains("语言：English")));
    }

    @Test void existingEnglishDraftReviewKeepsItsFrozenLanguageUnderChineseConfiguration() {
        var englishContent = new XContentPolicy("Adult relationships", "English", "concrete and restrained", "", 5, 120);
        var englishPolicy = new XWorkflowPolicy(true, true, true, "123", englishContent, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        XPost pending = XPost.generating("123", "earlier-english-draft", now, englishPolicy)
                .generated(new GeneratedXPost("Can we talk about what feels comfortable for both of us?", "An expression example."), now);
        state.set(pending);

        var currentDialogueContent = new XContentPolicy("成年人的暧昧", "简体中文", "自然", "仅短对话", 5, 120,
                XContentPolicy.Format.CHAT_DIALOGUE);
        var currentDialoguePolicy = new XWorkflowPolicy(true, true, true, "123", currentDialogueContent, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        XPost revised = service(currentDialoguePolicy, now).revise(pending.id(), pending.revision(),
                "I want to hear what feels comfortable for both of us.");

        assertEquals(englishContent, revised.contentPolicy());
        verify(reviews).submit(argThat(request -> request.version() == 2
                && request.context().contains("按冻结语言自然表达")
                && request.context().contains("语言：English")
                && !request.context().contains("语言：简体中文")
                && request.context().contains("冻结格式：FREE_TEXT")
                && !request.context().contains("短对话审核")
                && !request.context().contains("每句独占一行")
                && request.context().contains("原始 AI 说明不代表当前版本")));
    }

    @Test void disabledPublishingAndQuotaFenceBothPreventExternalSend() {
        XPost pending = service.generate().orElseThrow(); service.applyReview(decision(pending, true));
        var disabled = new XWorkflowPolicy(true, true, false, "123", content, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        service(disabled, now).runPublishing(); verify(publisher, never()).publish(any());
        when(repository.claimPublishing(any(), anyLong(), any(), anyInt(), any())).thenReturn(false);
        service.runPublishing(); verify(publisher, never()).publish(any());
        assertEquals(XPostStatus.APPROVED, state.get().status());
    }

    @Test void disabledPublishingRecordsOneWaitingHistoryAndPreservesApproval() {
        XPost pending = service.generate().orElseThrow();
        XPost approved = service.applyReview(decision(pending, true));
        var disabled = new XWorkflowPolicy(true, true, false, "123", content, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        clearInvocations(repository, publisher);

        service(disabled, now.plusSeconds(1)).runPublishing();
        XPost waiting = state.get();
        service(disabled, now.plusSeconds(61)).runPublishing();

        assertSame(waiting, state.get());
        assertEquals(XPostStatus.APPROVED, waiting.status());
        assertEquals("Waiting to publish: X workflow publishing is disabled", waiting.lastError());
        assertEquals(approved.revision() + 1, waiting.revision());
        assertEquals(approved.reviewedAt(), waiting.reviewedAt());
        assertEquals(approved.expiresAt(), waiting.expiresAt());
        verify(repository, times(1)).save(any(), eq(approved.revision()));
        verify(repository, never()).claimPublishing(any(), anyLong(), any(), anyInt(), any());
        verifyNoInteractions(publisher);
    }

    @Test void publisherUnavailableReasonIsSavedOnceWithoutClaimOrSend() {
        XPost pending = service.generate().orElseThrow();
        XPost approved = service.applyReview(decision(pending, true));
        when(publisher.credentialsAvailable("123")).thenReturn(false);
        when(publisher.unavailableReason("123")).thenReturn("X live publishing is disabled");
        clearInvocations(repository);

        service.runPublishing(); service.runPublishing();

        assertEquals("Waiting to publish: X live publishing is disabled", state.get().lastError());
        assertEquals(approved.revision() + 1, state.get().revision());
        verify(repository, times(1)).save(any(), eq(approved.revision()));
        verify(repository, never()).claimPublishing(any(), anyLong(), any(), anyInt(), any());
        verify(publisher, never()).publish(any());
    }

    @Test void unavailablePublisherMockWithoutDiagnosticUsesSafeFallback() {
        XPost pending = service.generate().orElseThrow(); service.applyReview(decision(pending, true));
        when(publisher.credentialsAvailable("123")).thenReturn(false);

        service.runPublishing();

        assertEquals("Waiting to publish: Publisher gates or credentials are unavailable", state.get().lastError());
        verify(publisher, never()).publish(any());
    }

    @Test void localQuotaOrIntervalWaitIsStableAndCanLaterPublish() {
        XPost pending = service.generate().orElseThrow();
        XPost approved = service.applyReview(decision(pending, true));
        when(repository.claimPublishing(any(), anyLong(), any(), anyInt(), any())).thenReturn(false);
        clearInvocations(repository);

        service.runPublishing(); service.runPublishing();

        assertEquals("Waiting to publish: local daily attempt limit or minimum interval", state.get().lastError());
        assertEquals(approved.revision() + 1, state.get().revision());
        verify(repository, times(1)).save(any(), eq(approved.revision()));
        verify(publisher, never()).publish(any());
        when(repository.claimPublishing(any(), anyLong(), any(), anyInt(), any())).thenAnswer(this::save);
        service.runPublishing();
        assertEquals(XPostStatus.PUBLISHED, state.get().status());
        assertNull(state.get().lastError());
        verify(publisher, times(1)).publish(any());
    }

    @Test void claimCasConflictNeverOverwritesAnotherPublishingClaimWithWaitingReason() {
        XPost pending = service.generate().orElseThrow(); service.applyReview(decision(pending, true));
        when(repository.claimPublishing(any(), anyLong(), any(), anyInt(), any())).thenAnswer(invocation -> {
            state.set(invocation.getArgument(0)); return false;
        });
        clearInvocations(repository);

        service.runPublishing();

        assertEquals(XPostStatus.PUBLISHING, state.get().status());
        assertNull(state.get().lastError());
        verify(repository, never()).save(any(), anyLong());
        verify(publisher, never()).publish(any());
    }

    @Test void preflightCreditFailurePreservesHttpStatusAndIsNeverRetried() {
        XPost pending = service.generate().orElseThrow(); service.applyReview(decision(pending, true));
        when(publisher.publish(any())).thenThrow(new XPublishingException("safe preflight failure", 402));

        service.runPublishing(); service.runPublishing();

        assertEquals(XPostStatus.FAILED, state.get().status());
        assertEquals("Publishing blocked before send: X preflight HTTP 402", state.get().lastError());
        verify(publisher, times(1)).publish(any());
    }

    @ParameterizedTest @CsvSource({"400,FAILED", "402,FAILED", "403,FAILED", "429,FAILED", "408,UNKNOWN", "500,UNKNOWN"})
    void responseClassificationNeverRetries(int http, XPostStatus outcome) {
        XPost pending = service.generate().orElseThrow(); service.applyReview(decision(pending, true));
        when(publisher.publish(any())).thenThrow(new XApiException(http));
        service.runPublishing(); service.runPublishing();
        assertEquals(outcome, state.get().status());
        assertEquals("X request failed: HTTP " + http, state.get().lastError());
        verify(publisher, times(1)).publish(any());
    }

    @Test void timeoutIsUnknownAndInterruptedClaimIsRecoveredWithoutResend() {
        XPost pending = service.generate().orElseThrow(); service.applyReview(decision(pending, true));
        when(publisher.publish(any())).thenThrow(new IllegalStateException("mock timeout"));
        service.runPublishing(); service.runPublishing();
        assertEquals(XPostStatus.UNKNOWN, state.get().status()); verify(publisher, times(1)).publish(any());
        state.set(pending.review(1, true, "telegram:42", "Telegram button review", now, now).claim(now));
        clearInvocations(publisher);
        service(policy, now.plusSeconds(301)).runPublishing();
        assertEquals(XPostStatus.UNKNOWN, state.get().status()); verifyNoInteractions(publisher);
    }

    @Test void failedFinishLeavesDurableClaimAndReviewReplayDoesNotSendAgain() {
        XPost pending = service.generate().orElseThrow(); var decision = decision(pending, true); callback(decision);
        when(repository.finishPublishing(any(), anyLong())).thenReturn(false);
        service.runReviews();
        assertEquals(XPostStatus.PUBLISHING, state.get().status());
        service.runReviews(); service.runPublishing();
        verify(publisher, times(1)).publish(any());
    }

    @Test void eligibleReviewCasFailureIsNotAcknowledgedAndStaleReviewIsFinal() {
        XPost pending = service.generate().orElseThrow(); var decision = decision(pending, true); callback(decision);
        when(repository.save(any(), anyLong())).thenReturn(false);
        assertThrows(IllegalStateException.class, service::runReviews);
        assertEquals(XPostStatus.PENDING_REVIEW, state.get().status());
        verify(publisher, never()).publish(any());
    }

    @Test void expiryAndTargetChangeCannotPublish() {
        XPost pending = service.generate().orElseThrow(); service.applyReview(decision(pending, true));
        service(policy, now.plus(policy.reviewTtl())).runPublishing();
        assertEquals(XPostStatus.EXPIRED, state.get().status()); verify(publisher, never()).publish(any());
        state.set(pending.review(1, true, "telegram:42", "Telegram button review", now, now));
        var other = new XWorkflowPolicy(true, true, true, "456", content, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        service(other, now).runPublishing(); verify(publisher, never()).publish(any());
        assertEquals("Waiting to publish: X target account differs from this draft", state.get().lastError());
    }

    private Boolean save(org.mockito.invocation.InvocationOnMock invocation) {
        XPost changed = invocation.getArgument(0); long expected = invocation.getArgument(1);
        if (state.get() == null || state.get().revision() != expected) return false;
        state.set(changed); return true;
    }
    private XPostService service(XWorkflowPolicy config, Instant time) {
        return new XPostService(repository, generator, reviews, publisher, config, 900000,
                Clock.fixed(time, ZoneOffset.UTC));
    }
    private XReviewDecision decision(XPost post, boolean approved) {
        return new XReviewDecision(post.id(), post.contentVersion(), approved, "telegram:42", "Telegram button review", now);
    }
    @SuppressWarnings("unchecked") private void callback(XReviewDecision decision) {
        doAnswer(invocation -> { ((Function<XReviewDecision, Boolean>) invocation.getArgument(0)).apply(decision); return null; })
                .when(reviews).poll(any());
    }
}
