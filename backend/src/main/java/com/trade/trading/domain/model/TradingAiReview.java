package com.trade.trading.domain.model;

import java.time.Instant;

/** Durable assessment, application intent/outcome, and one-shot notification status. */
public record TradingAiReview(String reviewId, String scope, Instant startedAt, Instant expiresAt,
                              String snapshotJson, String proposalJson, String outcome, String detail,
                              String notificationStatus, Instant completedAt) {}
