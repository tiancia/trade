package com.trade.x.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class XPostTest {
    private final Instant now = Instant.parse("2026-10-03T08:00:00.123456789Z");
    private final XContentPolicy content = new XContentPolicy("engineering", "en", "plain", "", 4, 20);
    private final XWorkflowPolicy policy = new XWorkflowPolicy(true, true, true, "42", content, 5, 3,
            Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5));

    @Test
    void aCompleteApprovedVersionPublishesOnceAndPreservesItsAudit() {
        XPost generating = XPost.generating("42", "generation-1", now, policy);
        assertEquals(XPostStatus.GENERATING, generating.status());
        assertEquals(0, generating.revision());
        assertEquals(now.truncatedTo(ChronoUnit.MICROS), generating.createdAt());
        assertEquals(content, generating.contentPolicy());
        XPost pending = generating.generated(new GeneratedXPost("  Useful content  ", " no external facts "), now);
        assertEquals("Useful content", pending.body());
        assertEquals("no external facts", pending.reviewNote());
        assertEquals(XPostStatus.PENDING_REVIEW, pending.status());
        assertThrows(IllegalStateException.class, () -> pending.claim(now));
        XPost approved = pending.review(1, true, "telegram:7", "checked", now, now);
        assertEquals(now.truncatedTo(ChronoUnit.MICROS), approved.reviewedAt());
        XPost claimed = approved.claim(now);
        assertEquals(XPostStatus.PUBLISHING, claimed.status());
        assertNotNull(claimed.attemptId());
        assertThrows(IllegalStateException.class, () -> claimed.claim(now));
        XPost published = claimed.finish(XPostStatus.PUBLISHED, "123456789", null, now.plusSeconds(1));
        assertEquals(XPostStatus.PUBLISHED, published.status());
        assertEquals("123456789", published.postId());
        assertEquals(claimed.attemptId(), published.attemptId());
        assertEquals(approved.reviewer(), published.reviewer());
        assertEquals(approved.reviewedAt(), published.reviewedAt());
        assertEquals(claimed.revision() + 1, published.revision());
        assertThrows(IllegalStateException.class, () -> published.claim(now));
        assertThrows(IllegalStateException.class, () -> published.revise("another body", now));
    }

    @Test
    void manualRevisionClearsApprovalAndKeepsTheOriginalPolicyAndExpiry() {
        XPost pending = draft();
        XPost approved = pending.review(1, true, "reviewer", "checked", now, now);
        XPost edited = approved.revise("another body", now.plusSeconds(1));
        assertEquals(XPostStatus.PENDING_REVIEW, edited.status());
        assertEquals(2, edited.contentVersion());
        assertNull(edited.reviewer());
        assertNull(edited.reviewedAt());
        assertNull(edited.reviewReason());
        assertNull(edited.attemptId());
        assertEquals(content, edited.contentPolicy());
        assertEquals(pending.expiresAt(), edited.expiresAt());
        assertThrows(IllegalStateException.class,
                () -> edited.review(1, true, "reviewer", null, now.plusSeconds(1), now.plusSeconds(1)));
        assertThrows(IllegalStateException.class,
                () -> edited.review(2, true, "reviewer", null, now, now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> edited.revise("short", now.plusSeconds(2))
                .revise("x".repeat(21), now.plusSeconds(2)));
    }

    @Test
    void publishingWaitKeepsApprovalAndExpiryWithoutRepeatedRevisionChanges() {
        XPost pending = draft();
        XPost approved = pending.review(1, true, "reviewer", "checked", now, now);
        String reason = "Waiting to publish: X workflow publishing is disabled";
        XPost waiting = approved.waitingToPublish(reason, now.plusSeconds(1));
        assertEquals(XPostStatus.APPROVED, waiting.status());
        assertEquals(approved.revision() + 1, waiting.revision());
        assertEquals(approved.contentVersion(), waiting.contentVersion());
        assertEquals(approved.body(), waiting.body());
        assertEquals(approved.reviewer(), waiting.reviewer());
        assertEquals(approved.reviewedAt(), waiting.reviewedAt());
        assertEquals(approved.reviewReason(), waiting.reviewReason());
        assertEquals(approved.expiresAt(), waiting.expiresAt());
        assertEquals(reason, waiting.lastError());
        assertSame(waiting, waiting.waitingToPublish(reason, now.plusSeconds(60)));
        assertNull(waiting.claim(now.plusSeconds(61)).lastError());
        assertThrows(IllegalStateException.class, () -> pending.waitingToPublish(reason, now));
        assertThrows(IllegalStateException.class, () -> waiting.waitingToPublish(reason, waiting.expiresAt()));
        assertThrows(IllegalArgumentException.class, () -> approved.waitingToPublish("x".repeat(257), now));
    }

    @Test
    void rejectedExpiredAndUnknownOutcomesCannotBePublishedOrBlindlyRetried() {
        XPost pending = draft();
        XPost rejected = pending.review(1, false, "reviewer", "no", now, now);
        assertThrows(IllegalStateException.class, () -> rejected.claim(now));
        assertEquals(XPostStatus.PENDING_REVIEW, rejected.revise("revised body", now).status());
        Instant expiry = pending.expiresAt();
        assertFalse(pending.live(expiry));
        assertEquals(XPostStatus.EXPIRED, pending.expire(expiry).status());
        assertThrows(IllegalStateException.class, () -> pending.revise("changed body", expiry));
        assertThrows(IllegalStateException.class, () -> pending.review(1, true, "reviewer", null, expiry, expiry));
        XPost approved = pending.review(1, true, "reviewer", "checked", now, now);
        XPost expiredApproval = approved.expire(expiry);
        assertEquals(approved.reviewedAt(), expiredApproval.reviewedAt());
        XPost unknown = approved.claim(now).finish(XPostStatus.UNKNOWN, null, "outcome uncertain", now);
        assertThrows(IllegalStateException.class, () -> unknown.claim(now));
        assertThrows(IllegalStateException.class, () -> unknown.revise("another body", now));
        XPost claimed = approved.claim(now);
        assertThrows(IllegalArgumentException.class, () -> claimed.finish(XPostStatus.APPROVED, null, null, now));
        assertThrows(IllegalArgumentException.class, () -> claimed.finish(XPostStatus.PUBLISHED, "", null, now));
    }

    @Test
    void generationFailureExpiredGenerationAndInvalidReviewAreFailClosed() {
        XPost generating = XPost.generating("42", "generation-1", now, policy);
        assertEquals(XPostStatus.GENERATION_FAILED, generating.generationFailed(now).status());
        assertEquals(XPostStatus.EXPIRED,
                generating.generated(new GeneratedXPost("Useful content", "review"), generating.expiresAt()).status());
        assertThrows(IllegalArgumentException.class,
                () -> generating.generated(new GeneratedXPost("x".repeat(21), "review"), now));
        assertThrows(IllegalArgumentException.class, () -> new GeneratedXPost("body", ""));
        assertThrows(IllegalArgumentException.class, () -> XPost.generating("43", "generation-1", now, policy));
        assertThrows(IllegalArgumentException.class, () -> XPost.generating("42", " ", now, policy));
        XPost pending = draft();
        assertThrows(IllegalStateException.class,
                () -> pending.review(1, true, "reviewer", null, now.plusSeconds(100), now));
        assertThrows(IllegalStateException.class, () -> pending.review(1, true, "", null, now, now));
        assertThrows(IllegalStateException.class, () -> pending.review(1, true, "reviewer", null, null, now));
        assertDoesNotThrow(pending::validatePublishingBody);
    }

    @Test
    void reviewValuesRejectMissingIdentityAndNormalizeTimeToDatabasePrecision() {
        assertEquals(now.truncatedTo(ChronoUnit.MICROS),
                new XReviewDecision("draft", 1, true, "telegram:7", null, now).decidedAt());
        assertEquals(now.truncatedTo(ChronoUnit.MICROS),
                new XReviewRequest("draft", 1, "42", "body", "context", now).expiresAt());
        assertThrows(IllegalArgumentException.class, () -> new XReviewDecision("draft", 0, true, "reviewer", null, now));
        assertThrows(IllegalArgumentException.class, () -> new XReviewDecision("draft", 1, true, " ", null, now));
        assertThrows(IllegalArgumentException.class, () -> new XReviewRequest("draft", 1, "@username", "body", "", now));
    }

    private XPost draft() {
        return XPost.generating("42", "generation-1", now, policy)
                .generated(new GeneratedXPost("Useful content", "review"), now);
    }
}
