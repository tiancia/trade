package com.trade.weibo.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

@Mapper
public interface WeiboMapper {
    void insertOAuthState(@Param("state") String state, @Param("expiresAt") Timestamp expiresAt);

    int consumeOAuthState(@Param("state") String state, @Param("usedAt") Timestamp usedAt);

    default void upsertAccountToken(WeiboAccountTokenRow row) {
        if (accountTokenExists(row.getUid())) {
            updateAccountToken(row);
        } else {
            insertAccountToken(row);
        }
    }

    int updateAccountToken(WeiboAccountTokenRow row);

    boolean accountTokenExists(String uid);

    void insertAccountToken(WeiboAccountTokenRow row);

    WeiboAccountTokenRow findCurrentToken();

    WeiboAccountTokenRow findValidToken(@Param("now") Timestamp now);

    WeiboAccountTokenRow findValidTokenForUid(@Param("uid") String uid, @Param("now") Timestamp now);
}
