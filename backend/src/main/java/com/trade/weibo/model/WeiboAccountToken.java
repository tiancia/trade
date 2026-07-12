package com.trade.weibo.model;

import java.time.Instant;

/**
 * Stored Weibo credential used by OAuth and publishing use cases.
 */
public record WeiboAccountToken(String uid, String accessToken, Instant expiresAt) {
}
