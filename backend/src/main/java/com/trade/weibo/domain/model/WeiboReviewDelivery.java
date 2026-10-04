package com.trade.weibo.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** A durable Telegram delivery and the first review decision received for it. */
public record WeiboReviewDelivery(String id, long botId, String postId, int version, long chatId,
                                  Long messageId, String status, Instant expiresAt, String callbackId,
                                  Boolean approved, String reviewer, Instant decidedAt, String decisionStatus) {
    public WeiboReviewDelivery {
        if (expiresAt != null) expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (decidedAt != null) decidedAt = decidedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
