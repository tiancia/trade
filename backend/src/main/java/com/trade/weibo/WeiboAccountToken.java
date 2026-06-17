package com.trade.weibo;

import java.time.Instant;

public record WeiboAccountToken(String uid, String accessToken, Instant expiresAt) {
}
