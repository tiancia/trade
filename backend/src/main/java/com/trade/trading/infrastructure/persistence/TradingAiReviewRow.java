package com.trade.trading.infrastructure.persistence;

import lombok.Data;
import java.time.Instant;

@Data
public class TradingAiReviewRow {
    private Long id;
    private String reviewId;
    private String scope;
    private Instant startedAt;
    private Instant expiresAt;
    private String snapshotJson;
    private String proposalJson;
    private String outcome;
    private String detail;
    private String notificationStatus;
    private Instant completedAt;
}
