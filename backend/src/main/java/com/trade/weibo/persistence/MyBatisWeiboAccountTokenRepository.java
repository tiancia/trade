package com.trade.weibo.persistence;

import com.trade.weibo.application.port.WeiboAccountTokenRepository;
import com.trade.weibo.model.WeiboAccountToken;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * MyBatis adapter for the account-token application port.
 */
@Repository
public class MyBatisWeiboAccountTokenRepository implements WeiboAccountTokenRepository {
    private final WeiboMapper mapper;

    public MyBatisWeiboAccountTokenRepository(WeiboMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void upsert(String uid, String accessToken, Instant expiresAt) {
        mapper.upsertAccountToken(new WeiboAccountTokenRow()
                .setUid(uid)
                .setAccessToken(accessToken)
                .setExpiresAt(Timestamp.from(expiresAt)));
    }

    @Override
    public Optional<WeiboAccountToken> findCurrent() {
        return Optional.ofNullable(mapper.findCurrentToken()).map(this::toToken);
    }

    @Override
    public Optional<WeiboAccountToken> findValid(Instant now) {
        return Optional.ofNullable(mapper.findValidToken(Timestamp.from(now))).map(this::toToken);
    }

    private WeiboAccountToken toToken(WeiboAccountTokenRow row) {
        return new WeiboAccountToken(
                row.getUid(),
                row.getAccessToken(),
                row.getExpiresAt() == null ? null : row.getExpiresAt().toInstant()
        );
    }
}
