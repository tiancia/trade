package com.trade.weibo.domain.model;

import java.time.Instant;

/** Created only after the future inbound adapter authenticates the reviewer and chat. */
public record ReviewDecision(String module, String reference, int version, boolean approved,
                             String reviewer, String reason, Instant decidedAt) {
    public ReviewDecision {
        if (module == null || module.isBlank() || reference == null || reference.isBlank()
                || version < 1 || reviewer == null || reviewer.isBlank() || reviewer.length() > 128
                || decidedAt == null || (reason != null && reason.length() > 2000)) {
            throw new IllegalArgumentException("Invalid review decision");
        }
    }
}
