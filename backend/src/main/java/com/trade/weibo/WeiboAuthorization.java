package com.trade.weibo;

import java.time.Instant;

public record WeiboAuthorization(String uid, Instant expiresAt, boolean authorized) {
}
