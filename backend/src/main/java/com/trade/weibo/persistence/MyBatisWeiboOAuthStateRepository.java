package com.trade.weibo.persistence;

import com.trade.weibo.application.port.WeiboOAuthStateRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * MyBatis adapter that makes OAuth states single-use and time-bound.
 */
@Repository
public class MyBatisWeiboOAuthStateRepository implements WeiboOAuthStateRepository {
    private final WeiboMapper mapper;

    public MyBatisWeiboOAuthStateRepository(WeiboMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(String state, Instant expiresAt) {
        mapper.insertOAuthState(state, Timestamp.from(expiresAt));
    }

    @Override
    public boolean consume(String state, Instant usedAt) {
        return mapper.consumeOAuthState(state, Timestamp.from(usedAt)) == 1;
    }
}
