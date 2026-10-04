package com.trade.x.application.service;

import com.trade.client.x.XApiException;
import com.trade.x.application.port.*;
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
                && request.context().contains("开发经验")));
        verify(publisher, never()).publish(any());
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

    @Test void frozenContentBoundsSurviveConfigurationChange() {
        XPost pending = service.generate().orElseThrow();
        var changedContent = new XContentPolicy("新的方向", "English", "formal", "", 100, 120);
        var changedPolicy = new XWorkflowPolicy(true, true, true, "123", changedContent, 5, 3,
                policy.reviewTtl(), policy.publishInterval(), policy.claimTimeout());
        var changedService = service(changedPolicy, now);
        XPost revised = changedService.revise(pending.id(), pending.revision(), "短正文遵守原有规则。");
        assertEquals(content, revised.contentPolicy());
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

    @ParameterizedTest @CsvSource({"400,FAILED", "403,FAILED", "429,FAILED", "408,UNKNOWN", "500,UNKNOWN"})
    void responseClassificationNeverRetries(int http, XPostStatus outcome) {
        XPost pending = service.generate().orElseThrow(); service.applyReview(decision(pending, true));
        when(publisher.publish(any())).thenThrow(new XApiException(http));
        service.runPublishing(); service.runPublishing();
        assertEquals(outcome, state.get().status()); verify(publisher, times(1)).publish(any());
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
