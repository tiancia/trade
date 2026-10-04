package com.trade.x.domain.model;

import java.time.Duration;
import java.util.Objects;

/** Runtime-independent generation, review and publishing gates. */
public record XWorkflowPolicy(boolean enabled, boolean generationEnabled, boolean publishingEnabled,
                              String targetUserId, XContentPolicy content, int dailyGenerationLimit,
                              int dailyPublishLimit, Duration reviewTtl, Duration publishInterval,
                              Duration claimTimeout) {
    public XWorkflowPolicy {
        targetUserId = targetUserId == null ? "" : targetUserId.strip();
        if ((!targetUserId.isEmpty() && !validTarget(targetUserId)) || (enabled && targetUserId.isEmpty())
                || content == null || dailyGenerationLimit < 1 || dailyPublishLimit < 1
                || !positive(reviewTtl) || !positive(publishInterval) || !positive(claimTimeout)
                || claimTimeout.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("Invalid X workflow policy");
        }
    }

    public void requireEnabled() {
        if (!enabled) throw new IllegalStateException("X workflow is disabled");
    }

    public void requireGeneration() {
        requireEnabled();
        if (!generationEnabled || !validTarget(targetUserId) || content.direction().isBlank()) {
            throw new IllegalStateException("X generation is disabled or target/content direction is missing");
        }
    }

    public void requirePublishing() {
        requireEnabled();
        if (!publishingEnabled || !validTarget(targetUserId)) {
            throw new IllegalStateException("X publishing is disabled or target user is missing");
        }
    }

    public void validateTarget(String actualUserId) {
        if (actualUserId == null || !validTarget(actualUserId.strip())
                || !targetUserId.equals(actualUserId.strip())) {
            throw new IllegalArgumentException("X post belongs to another target user");
        }
    }

    private static boolean validTarget(String value) { return value.matches("[1-9][0-9]{0,18}"); }
    private static boolean positive(Duration value) {
        return Objects.nonNull(value) && !value.isNegative() && !value.isZero();
    }
}
