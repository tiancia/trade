package com.trade.trading.port;

import com.trade.trading.domain.model.TradingAiReview;
import java.time.Instant;
import java.util.List;

public interface TradingAiReviewStore {
    boolean claim(String scope, String reviewId, Instant now, Instant nextReviewAt, Instant expiresAt);
    void capture(String reviewId, String snapshotJson);
    void proposed(String reviewId, String snapshotJson, String proposalJson);
    boolean beginApplying(String reviewId, Instant now);
    void complete(String reviewId, String outcome, String detail, Instant now);
    boolean claimNotification(String reviewId, Instant now, Instant nextNotificationAt);
    void notification(String reviewId, String status);
    List<TradingAiReview> recent(String scope, int limit);
}
