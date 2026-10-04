package com.trade.x.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Complete immutable version sent for human review, including its actual target account. */
public record XReviewRequest(String reference, int version, String targetUserId, String content,
                             String context, Instant expiresAt) {
    public XReviewRequest {
        if (reference == null || reference.isBlank() || version < 1 || targetUserId == null
                || !targetUserId.matches("[1-9][0-9]{0,18}") || content == null || content.isBlank()
                || expiresAt == null) {
            throw new IllegalArgumentException("Invalid X review request");
        }
        expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    }
}
