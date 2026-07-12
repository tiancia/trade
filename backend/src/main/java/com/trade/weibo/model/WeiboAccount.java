package com.trade.weibo.model;

import java.time.Instant;

/**
 * Public view of the currently authorized Weibo account.
 */
public record WeiboAccount(String uid, boolean tokenValid, Instant expiresAt) {
}
