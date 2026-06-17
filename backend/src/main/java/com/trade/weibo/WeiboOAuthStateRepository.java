package com.trade.weibo;

import java.time.Instant;

public interface WeiboOAuthStateRepository {
    void save(String state, Instant expiresAt);

    boolean consume(String state, Instant usedAt);
}
