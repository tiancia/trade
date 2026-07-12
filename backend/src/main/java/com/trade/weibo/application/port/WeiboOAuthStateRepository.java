package com.trade.weibo.application.port;

import java.time.Instant;

/**
 * Application port for one-time OAuth state storage.
 */
public interface WeiboOAuthStateRepository {
    void save(String state, Instant expiresAt);

    boolean consume(String state, Instant usedAt);
}
