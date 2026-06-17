package com.trade.weibo.persistence;

import com.trade.weibo.WeiboAccountToken;
import com.trade.weibo.WeiboAccountTokenRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

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
