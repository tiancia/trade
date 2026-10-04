package com.trade.x.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Immutable draft; revision fences persistence and contentVersion fences human approval. */
public record XPost(String id, String targetUserId, String generationKey, XContentPolicy contentPolicy,
                    String body, String reviewNote, int contentVersion, long revision, XPostStatus status,
                    Instant createdAt, Instant updatedAt, Instant expiresAt, String reviewer,
                    Instant reviewedAt, String reviewReason, String attemptId, Instant publishStartedAt,
                    String postId, String lastError) {
    public XPost {
        Objects.requireNonNull(contentPolicy, "X draft content policy is required");
        createdAt = time(createdAt);
        updatedAt = time(updatedAt);
        expiresAt = time(expiresAt);
        reviewedAt = time(reviewedAt);
        publishStartedAt = time(publishStartedAt);
    }

    public static XPost generating(String uid, String key, Instant now, XWorkflowPolicy policy) {
        Objects.requireNonNull(policy, "X workflow policy is required");
        policy.requireGeneration();
        policy.validateTarget(uid);
        if (key == null || key.isBlank() || key.length() > 128 || XContentPolicy.hasInvalidCharacters(key)) {
            throw new IllegalArgumentException("X generation key is required and must be bounded");
        }
        Instant timestamp = Objects.requireNonNull(time(now), "X generation time is required");
        return new XPost(UUID.randomUUID().toString(), uid.strip(), key.strip(), policy.content(), null, null,
                1, 0, XPostStatus.GENERATING, timestamp, timestamp, timestamp.plus(policy.reviewTtl()),
                null, null, null, null, null, null, null);
    }

    public XPost generated(GeneratedXPost generated, Instant now) {
        require(XPostStatus.GENERATING);
        Objects.requireNonNull(generated, "X generated content is required");
        return changed(contentPolicy.validateBody(generated.body()), generated.reviewNote(), contentVersion,
                live(now) ? XPostStatus.PENDING_REVIEW : XPostStatus.EXPIRED,
                now, null, null, null, null, null, null, null);
    }

    public XPost generationFailed(Instant now) {
        require(XPostStatus.GENERATING);
        return changed(body, reviewNote, contentVersion, XPostStatus.GENERATION_FAILED, now,
                null, null, null, null, null, null, "Generation failed; check content and AI configuration");
    }

    public XPost revise(String newBody, Instant now) {
        if (!(status == XPostStatus.PENDING_REVIEW || status == XPostStatus.APPROVED
                || status == XPostStatus.REJECTED || status == XPostStatus.GENERATION_FAILED)) {
            throw new IllegalStateException("This X post cannot be edited");
        }
        if (!live(now)) throw new IllegalStateException("X post has expired");
        return changed(contentPolicy.validateBody(newBody), reviewNote, contentVersion + 1,
                XPostStatus.PENDING_REVIEW, now, null, null, null, null, null, null, null);
    }

    public XPost review(int version, boolean approved, String actor, String reason, Instant decisionAt, Instant now) {
        require(XPostStatus.PENDING_REVIEW);
        Instant decision = time(decisionAt);
        if (version != contentVersion || !live(now) || decision == null || decision.isBefore(updatedAt)
                || decision.isAfter(now.plusSeconds(30)) || actor == null || actor.isBlank() || actor.length() > 128
                || (reason != null && reason.length() > 2000)) {
            throw new IllegalStateException("X review is stale, expired or invalid");
        }
        return changed(body, reviewNote, contentVersion, approved ? XPostStatus.APPROVED : XPostStatus.REJECTED,
                now, actor, decision, reason, null, null, null, null);
    }

    public XPost claim(Instant now) {
        require(XPostStatus.APPROVED);
        if (!live(now) || reviewedAt == null || reviewer == null || reviewer.isBlank()) {
            throw new IllegalStateException("X post is not eligible for publishing");
        }
        return changed(body, reviewNote, contentVersion, XPostStatus.PUBLISHING, now, reviewer, reviewedAt,
                reviewReason, UUID.randomUUID().toString(), now, null, null);
    }

    public XPost finish(XPostStatus outcome, String publishedId, String error, Instant now) {
        require(XPostStatus.PUBLISHING);
        if (!(outcome == XPostStatus.PUBLISHED || outcome == XPostStatus.FAILED || outcome == XPostStatus.UNKNOWN)) {
            throw new IllegalArgumentException("Invalid X publishing outcome");
        }
        if (outcome == XPostStatus.PUBLISHED && (publishedId == null || !publishedId.matches("[1-9][0-9]{0,18}"))) {
            throw new IllegalArgumentException("Missing or invalid X post ID");
        }
        return changed(body, reviewNote, contentVersion, outcome, now, reviewer, reviewedAt,
                reviewReason, attemptId, publishStartedAt, publishedId, error);
    }

    public XPost expire(Instant now) {
        if (live(now) || !(status == XPostStatus.PENDING_REVIEW || status == XPostStatus.APPROVED)) {
            throw new IllegalStateException("X post cannot be expired");
        }
        return changed(body, reviewNote, contentVersion, XPostStatus.EXPIRED, now, reviewer, reviewedAt,
                reviewReason, attemptId, publishStartedAt, postId, lastError);
    }

    public boolean live(Instant now) { return expiresAt.isAfter(now); }
    public void validatePublishingBody() { contentPolicy.validateBody(body); }

    private void require(XPostStatus expected) {
        if (status != expected) throw new IllegalStateException("Unexpected X post state: " + status);
    }

    private XPost changed(String text, String note, int version, XPostStatus state, Instant now, String actor,
                          Instant reviewed, String reason, String attempt, Instant started, String published,
                          String error) {
        return new XPost(id, targetUserId, generationKey, contentPolicy, text, note, version, revision + 1,
                state, createdAt, now, expiresAt, actor, reviewed, reason, attempt, started, published, error);
    }

    private static Instant time(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}
