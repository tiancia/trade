package com.trade.weibo.domain.model;

import java.time.Duration;
import java.util.Objects;

/** Runtime-independent limits; configuration binding belongs to infrastructure. */
public record WeiboWorkflowPolicy(boolean enabled, boolean generationEnabled, boolean publishingEnabled,
                                  String targetUid, int maxBodyChars, int dailyGenerationLimit,
                                  int dailyPublishLimit, Duration eventMaxAge, Duration reviewTtl,
                                  Duration publishInterval, Duration claimTimeout) {
    public WeiboWorkflowPolicy {
        targetUid = targetUid == null ? "" : targetUid.trim();
        if (targetUid.length() > 64 || maxBodyChars < 1 || maxBodyChars > 10000
                || dailyGenerationLimit < 1 || dailyPublishLimit < 1
                || !positive(eventMaxAge) || !positive(reviewTtl)
                || !positive(publishInterval) || !positive(claimTimeout)
                || claimTimeout.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("Invalid Weibo workflow limits");
        }
    }

    public void requireGeneration() {
        requireEnabled();
        if (!generationEnabled || targetUid.isBlank()) {
            throw new IllegalStateException("Weibo generation is disabled or target UID is missing");
        }
    }

    public void requirePublishing() {
        requireEnabled();
        if (!publishingEnabled || targetUid.isBlank()) {
            throw new IllegalStateException("Weibo publishing is disabled or target UID is missing");
        }
    }

    public void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Weibo workflow is disabled");
    }

    private static boolean positive(Duration value) {
        return Objects.nonNull(value) && !value.isNegative() && !value.isZero();
    }
}
