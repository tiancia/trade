package com.trade.weibo.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable aggregate. All business transitions live here; revision is the CAS fence. */
public record WeiboPost(String id, String targetUid, HotEvent event, String body, String reviewNote,
                        int contentVersion, long revision, WeiboPostStatus status, Instant createdAt,
                        Instant updatedAt, Instant expiresAt, String reviewer, Instant reviewedAt,
                        String reviewReason, String attemptId, Instant publishStartedAt,
                        String weiboId, String lastError) {
    public static WeiboPost generating(String uid, HotEvent event, Instant now, WeiboWorkflowPolicy policy) {
        if (!event.occurredAt().plus(policy.eventMaxAge()).isAfter(now)) {
            throw new IllegalArgumentException("Event has expired");
        }
        Instant expiresAt = now.plus(policy.reviewTtl());
        Instant eventExpiry = event.occurredAt().plus(policy.eventMaxAge());
        if (eventExpiry.isBefore(expiresAt)) expiresAt = eventExpiry;
        return new WeiboPost(UUID.randomUUID().toString(), uid, event, null, null, 1, 0,
                WeiboPostStatus.GENERATING, now, now, expiresAt, null, null, null, null, null, null, null);
    }

    public WeiboPost generated(GeneratedComment comment, Instant now, int maxChars) {
        require(WeiboPostStatus.GENERATING);
        return changed(validBody(comment.body(), maxChars), validNote(comment.reviewNote()), contentVersion,
                live(now) ? WeiboPostStatus.PENDING_REVIEW : WeiboPostStatus.EXPIRED,
                now, null, null, null, null, null, null, null);
    }

    public WeiboPost generationFailed(Instant now) {
        require(WeiboPostStatus.GENERATING);
        return changed(body, reviewNote, contentVersion, WeiboPostStatus.GENERATION_FAILED, now,
                null, null, null, null, null, null, "Generation failed; check source and AI configuration");
    }

    public WeiboPost revise(String newBody, Instant now, int maxChars) {
        if (!(status == WeiboPostStatus.PENDING_REVIEW || status == WeiboPostStatus.APPROVED
                || status == WeiboPostStatus.REJECTED || status == WeiboPostStatus.GENERATION_FAILED)) {
            throw new IllegalStateException("This post cannot be edited");
        }
        if (!live(now)) throw new IllegalStateException("Post has expired");
        return changed(validBody(newBody, maxChars), reviewNote, contentVersion + 1,
                WeiboPostStatus.PENDING_REVIEW, now, null, null, null, null, null, null, null);
    }

    public WeiboPost review(int version, boolean approved, String actor, String reason, Instant decisionAt,
                            Instant now) {
        require(WeiboPostStatus.PENDING_REVIEW);
        if (version != contentVersion || !live(now) || decisionAt.isBefore(updatedAt)
                || decisionAt.isAfter(now.plusSeconds(30)) || actor == null || actor.isBlank()) {
            throw new IllegalStateException("Review is stale, expired or invalid");
        }
        return changed(body, reviewNote, contentVersion,
                approved ? WeiboPostStatus.APPROVED : WeiboPostStatus.REJECTED,
                now, actor, decisionAt, reason, null, null, null, null);
    }

    public WeiboPost claim(Instant now) {
        require(WeiboPostStatus.APPROVED);
        if (!live(now) || reviewedAt == null || reviewer == null) {
            throw new IllegalStateException("Post is not eligible for publishing");
        }
        return changed(body, reviewNote, contentVersion, WeiboPostStatus.PUBLISHING, now,
                reviewer, reviewedAt, reviewReason, UUID.randomUUID().toString(), now, null, null);
    }

    public WeiboPost finish(WeiboPostStatus outcome, String publishedId, String error, Instant now) {
        require(WeiboPostStatus.PUBLISHING);
        if (!(outcome == WeiboPostStatus.PUBLISHED || outcome == WeiboPostStatus.FAILED
                || outcome == WeiboPostStatus.UNKNOWN)) throw new IllegalArgumentException("Invalid outcome");
        if (outcome == WeiboPostStatus.PUBLISHED && (publishedId == null || publishedId.isBlank()
                || publishedId.length() > 128)) throw new IllegalArgumentException("Missing or invalid Weibo ID");
        return changed(body, reviewNote, contentVersion, outcome, now, reviewer, reviewedAt, reviewReason,
                attemptId, publishStartedAt, publishedId, error);
    }

    public WeiboPost expire(Instant now) {
        if (live(now) || !(status == WeiboPostStatus.PENDING_REVIEW || status == WeiboPostStatus.APPROVED)) {
            throw new IllegalStateException("Post cannot be expired");
        }
        return changed(body, reviewNote, contentVersion, WeiboPostStatus.EXPIRED, now,
                reviewer, reviewedAt, reviewReason, attemptId, publishStartedAt, weiboId, lastError);
    }

    public boolean live(Instant now) { return expiresAt.isAfter(now); }

    public void validatePublishingBody(int maxChars) { validBody(body, maxChars); }

    private void require(WeiboPostStatus expected) {
        if (status != expected) throw new IllegalStateException("Unexpected post state: " + status);
    }

    private WeiboPost changed(String text, String note, int version, WeiboPostStatus state, Instant now,
                               String actor, Instant reviewed, String reason, String attempt,
                               Instant started, String published, String error) {
        return new WeiboPost(id, targetUid, event, text, note, version, revision + 1, state,
                createdAt, now, expiresAt, actor, reviewed, reason, attempt, started, published, error);
    }

    private static String validBody(String value, int maxChars) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Post body is required");
        String text = value.trim();
        if (text.codePointCount(0, text.length()) > maxChars || text.length() > 20000
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new IllegalArgumentException("Post body exceeds limits or contains control characters");
        }
        return text;
    }

    private static String validNote(String value) {
        if (value == null || value.isBlank() || value.length() > 2000) {
            throw new IllegalArgumentException("AI review note is required and must be bounded");
        }
        return value.trim();
    }
}
