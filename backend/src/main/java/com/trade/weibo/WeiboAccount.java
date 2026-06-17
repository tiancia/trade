package com.trade.weibo;

import java.time.Instant;

public record WeiboAccount(String uid, boolean tokenValid, Instant expiresAt) {
}
