package com.trade.weibo.model;

import java.time.Instant;

/**
 * Short-lived authorization URL and its matching anti-forgery state.
 */
public record WeiboAuthorizeUrl(String authorizeUrl, String state, Instant expiresAt) {
}
