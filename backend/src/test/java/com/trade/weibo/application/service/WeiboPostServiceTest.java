package com.trade.weibo.application.service;

import com.trade.client.weibo.WeiboHttpException;
import com.trade.telegram.application.port.HumanReviewGateway;
import com.trade.telegram.domain.model.ReviewDecision;
import com.trade.weibo.application.port.HotEventSource;
import com.trade.weibo.application.port.WeiboDraftGenerator;
import com.trade.weibo.application.port.WeiboPostPublisher;
import com.trade.weibo.application.port.WeiboPostRepository;
import com.trade.weibo.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeiboPostServiceTest {
    private final Instant now = Instant.parse("2026-10-02T01:00:00Z");
    private final WeiboWorkflowPolicy policy = new WeiboWorkflowPolicy(true, true, true, "uid", 280, 5, 3,
            Duration.ofHours(24), Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5));
    private final HotEvent event = new HotEvent("事件", "https://example.test/news/1", "来源事实摘要", now, now);
    private final WeiboPostRepository posts = mock(WeiboPostRepository.class);
    private final HotEventSource source = mock(HotEventSource.class);
    private final WeiboDraftGenerator generator = mock(WeiboDraftGenerator.class);
    private final HumanReviewGateway reviews = mock(HumanReviewGateway.class);
    private final WeiboPostPublisher publisher = mock(WeiboPostPublisher.class);
    private final Map<String, WeiboPost> stored = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        when(generator.generate(any(), anyInt())).thenReturn(new GeneratedComment("观点正文", "待核实事项"));
        when(posts.reserveGeneration(any(), any(), anyInt())).thenAnswer(call -> {
            WeiboPost post = call.getArgument(0);
            if (stored.values().stream().anyMatch(p -> p.event().key().equals(post.event().key()))) return false;
            stored.put(post.id(), post);
            return true;
        });
        when(posts.find(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(posts.actionable(anyInt())).thenAnswer(call -> stored.values().stream().filter(p -> switch (p.status()) {
            case GENERATING, APPROVED, PUBLISHING, PENDING_REVIEW -> true;
            default -> false;
        }).toList());
        when(posts.save(any(), anyLong())).thenAnswer(call -> save(call.getArgument(0), call.getArgument(1)));
        when(posts.claimPublishing(any(), anyLong(), any(), anyInt(), any()))
                .thenAnswer(call -> save(call.getArgument(0), call.getArgument(1)));
        when(posts.finishPublishing(any(), anyLong())).thenAnswer(call -> save(call.getArgument(0), call.getArgument(1)));
        when(publisher.credentialsAvailable("uid")).thenReturn(true);
        when(publisher.publish(any())).thenReturn("weibo-123");
    }

    @Test
    void unavailableTelegramLeavesDraftPendingAndEventIsNotGeneratedTwice() {
        WeiboPost draft = service(now).generate(event).orElseThrow();
        assertEquals(WeiboPostStatus.PENDING_REVIEW, draft.status());
        assertTrue(service(now).generate(event).isEmpty());
        service(now).runPublishing();
        assertEquals(WeiboPostStatus.PENDING_REVIEW, service(now).get(draft.id()).status());
        verify(generator, times(1)).generate(any(), anyInt());
        verify(reviews, atLeastOnce()).submit(argThat(request -> request.module().equals("weibo")
                && request.version() == 1 && request.content().equals("观点正文")));
        verify(publisher, never()).publish(any());
    }

    @Test
    void approvedExactVersionPublishesOnceAndKeepsResult() {
        WeiboPost draft = generateAndApprove();
        service(now).runPublishing();
        service(now).runPublishing();
        WeiboPost published = service(now).get(draft.id());
        assertEquals(WeiboPostStatus.PUBLISHED, published.status());
        assertEquals("weibo-123", published.weiboId());
        verify(publisher, times(1)).publish(argThat(p -> p.body().equals(draft.body()) && p.targetUid().equals("uid")));
    }

    @Test
    void editInvalidatesApprovalAndStaleCallbackCannotApproveNewVersion() {
        WeiboPost approved = generateAndApprove();
        WeiboPost edited = service(now).revise(approved.id(), approved.revision(), "修改正文");
        assertEquals(2, edited.contentVersion());
        assertNull(edited.reviewer());
        assertThrows(IllegalStateException.class, () -> service(now).applyReview(decision(approved, true)));
        assertThrows(IllegalStateException.class,
                () -> service(now).revise(edited.id(), approved.revision(), "覆盖正文"));
        service(now).runPublishing();
        verify(publisher, never()).publish(any());
    }

    @Test
    void networkFailureAndServerFailureAreUnknownAndNeverRetried() {
        WeiboPost draft = generateAndApprove();
        when(publisher.publish(any())).thenThrow(new RuntimeException("secret provider payload"));
        service(now).runPublishing();
        assertEquals(WeiboPostStatus.UNKNOWN, service(now).get(draft.id()).status());
        assertFalse(service(now).get(draft.id()).lastError().contains("secret"));
        service(now).runPublishing();
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void definitiveRejectionFailsAndDoesNotRetry() {
        WeiboPost draft = generateAndApprove();
        when(publisher.publish(any())).thenThrow(new WeiboHttpException("POST", "safe", 403, "sensitive"));
        service(now).runPublishing();
        service(now).runPublishing();
        assertEquals(WeiboPostStatus.FAILED, service(now).get(draft.id()).status());
        assertEquals("Weibo request failed: HTTP 403", service(now).get(draft.id()).lastError());
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void quotaCasAndMissingCredentialsPreventOutboundCalls() {
        WeiboPost draft = generateAndApprove();
        doReturn(false).when(posts).claimPublishing(any(), anyLong(), any(), anyInt(), any());
        service(now).runPublishing();
        when(publisher.credentialsAvailable("uid")).thenReturn(false);
        service(now).runPublishing();
        assertEquals(WeiboPostStatus.APPROVED, service(now).get(draft.id()).status());
        verify(publisher, never()).publish(any());
    }

    @Test
    void recoveryAfterCrashMarksUnknownWithoutSendingAgain() {
        WeiboPost draft = generateAndApprove();
        WeiboPost claimed = draft.claim(now);
        stored.put(claimed.id(), claimed);
        service(now.plusSeconds(301)).runPublishing();
        assertEquals(WeiboPostStatus.UNKNOWN, service(now).get(draft.id()).status());
        verify(publisher, never()).publish(any());
    }

    @Test
    void generationFailuresAndExpiredPostsCannotPublish() {
        when(generator.generate(any(), anyInt())).thenThrow(new RuntimeException("secret"));
        WeiboPost failed = service(now).generate(event).orElseThrow();
        assertEquals(WeiboPostStatus.GENERATION_FAILED, failed.status());
        stored.clear();
        doReturn(new GeneratedComment("正文", "说明")).when(generator).generate(any(), anyInt());
        WeiboPost draft = generateAndApprove();
        service(draft.expiresAt()).runPublishing();
        assertEquals(WeiboPostStatus.EXPIRED, service(now).get(draft.id()).status());
        verify(publisher, never()).publish(any());
    }

    @Test
    void disabledWorkflowDoesNotFetchGenerateOrPublish() {
        WeiboWorkflowPolicy disabled = new WeiboWorkflowPolicy(false, false, false, "", 280, 5, 3,
                Duration.ofHours(24), Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5));
        WeiboPostService service = new WeiboPostService(posts, source, generator, reviews, publisher, disabled,
                Clock.fixed(now, ZoneOffset.UTC));
        service.runGeneration();
        service.runPublishing();
        assertThrows(IllegalStateException.class, () -> service.generate(event));
        verify(source, never()).collect();
        verify(generator, never()).generate(any(), anyInt());
        verify(publisher, never()).publish(any());
    }

    private WeiboPost generateAndApprove() {
        WeiboPost draft = service(now).generate(event).orElseThrow();
        ReviewDecision decision = decision(draft, true);
        WeiboPost approved = service(now).applyReview(decision);
        assertEquals(approved, service(now).applyReview(decision));
        return approved;
    }

    private ReviewDecision decision(WeiboPost draft, boolean approved) {
        return new ReviewDecision("weibo", draft.id(), draft.contentVersion(), approved, "reviewer", "checked", now);
    }

    private boolean save(WeiboPost changed, long expected) {
        if (stored.get(changed.id()).revision() != expected) return false;
        stored.put(changed.id(), changed);
        return true;
    }

    private WeiboPostService service(Instant at) {
        return new WeiboPostService(posts, source, generator, reviews, publisher, policy, Clock.fixed(at, ZoneOffset.UTC));
    }
}
