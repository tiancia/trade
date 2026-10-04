package com.trade.x.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Created only after the reviewer, chat and delivered message have been authenticated. */
public record XReviewDecision(String reference, int version, boolean approved, String reviewer,
                              String reason, Instant decidedAt) {
    public XReviewDecision {
        if (reference == null || reference.isBlank() || version < 1 || reviewer == null || reviewer.isBlank()
                || reviewer.length() > 128 || decidedAt == null || (reason != null && reason.length() > 2000)) {
            throw new IllegalArgumentException("Invalid X review decision");
        }
        decidedAt = decidedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
