package com.trade.weibo.application.port;

import com.trade.weibo.domain.model.WeiboReviewDelivery;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Durable delivery identities, decisions, and one polling cursor/lease per bot. */
public interface WeiboReviewDeliveryStore {
    boolean reserve(WeiboReviewDelivery delivery);
    Optional<WeiboReviewDelivery> find(long botId, String id);
    Optional<WeiboReviewDelivery> findForPost(long botId, String postId, int version);
    boolean markSent(long botId, String id, long messageId);
    void markUnknown(long botId, String id);
    boolean recordDecision(long botId, String id, String callbackId, boolean approved, String reviewer, Instant decidedAt);
    void finishDecision(long botId, String id, boolean applied);
    OptionalLong acquirePolling(long botId, String owner, Instant now, Instant until);
    boolean renewPolling(long botId, String owner, Instant now, Instant until);
    boolean advancePolling(long botId, String owner, long nextOffset, Instant now, Instant until);
    void releasePolling(long botId, String owner);
}
