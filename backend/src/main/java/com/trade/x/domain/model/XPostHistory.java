package com.trade.x.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record XPostHistory(long revision, int contentVersion, XPostStatus status, String body,
                           String reviewer, String reviewReason, String attemptId, String postId,
                           String lastError, Instant updatedAt) {
    public XPostHistory {
        if (updatedAt != null) updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
