package com.trade.weibo;

import java.time.Instant;

public record WeiboAuthorizeUrl(String authorizeUrl, String state, Instant expiresAt) {
}
