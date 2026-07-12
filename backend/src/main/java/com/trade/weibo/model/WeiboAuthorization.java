package com.trade.weibo.model;

import java.time.Instant;

/**
 * Result returned after a successful OAuth callback.
 */
public record WeiboAuthorization(String uid, Instant expiresAt, boolean authorized) {
}
