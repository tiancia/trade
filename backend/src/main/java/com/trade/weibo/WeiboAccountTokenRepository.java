package com.trade.weibo;

import java.time.Instant;
import java.util.Optional;

public interface WeiboAccountTokenRepository {
    void upsert(String uid, String accessToken, Instant expiresAt);

    Optional<WeiboAccountToken> findCurrent();

    Optional<WeiboAccountToken> findValid(Instant now);
}
