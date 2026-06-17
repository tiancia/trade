package com.trade.weibo.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

@Mapper
public interface WeiboMapper {
    void insertOAuthState(@Param("state") String state, @Param("expiresAt") Timestamp expiresAt);

    int consumeOAuthState(@Param("state") String state, @Param("usedAt") Timestamp usedAt);

    void upsertAccountToken(WeiboAccountTokenRow row);

    WeiboAccountTokenRow findCurrentToken();

    WeiboAccountTokenRow findValidToken(@Param("now") Timestamp now);
}
