package com.trade.weibo.application.port;

import com.trade.weibo.domain.model.WeiboAccountToken;

import java.time.Instant;
import java.util.Optional;

/**
 * Application port for storing and resolving Weibo access tokens.
 *
 * <p>The interface lives with the use cases; database-specific implementations
 * belong in {@code com.trade.weibo.infrastructure.persistence}.</p>
 */
public interface WeiboAccountTokenRepository {
    void upsert(String uid, String accessToken, Instant expiresAt);

    Optional<WeiboAccountToken> findCurrent();

    Optional<WeiboAccountToken> findValid(Instant now);
}
