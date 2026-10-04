package com.trade.weibo.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

@Mapper
public interface WeiboReviewDeliveryMapper {
    int insert(WeiboReviewDeliveryRow row);
    WeiboReviewDeliveryRow find(@Param("botId") long botId, @Param("id") String id);
    WeiboReviewDeliveryRow findForPost(@Param("botId") long botId, @Param("postId") String postId, @Param("version") int version);
    int markSent(@Param("botId") long botId, @Param("id") String id, @Param("messageId") long messageId);
    int markUnknown(@Param("botId") long botId, @Param("id") String id);
    int recordDecision(@Param("botId") long botId, @Param("id") String id, @Param("callbackId") String callbackId,
                       @Param("approved") boolean approved, @Param("reviewer") String reviewer,
                       @Param("decidedAt") Timestamp decidedAt);
    int finishDecision(@Param("botId") long botId, @Param("id") String id, @Param("decisionStatus") String decisionStatus);
    void ensurePolling(@Param("botId") long botId);
    int acquirePolling(@Param("botId") long botId, @Param("owner") String owner,
                       @Param("now") Timestamp now, @Param("until") Timestamp until);
    Long pollingOffset(@Param("botId") long botId);
    int renewPolling(@Param("botId") long botId, @Param("owner") String owner,
                     @Param("now") Timestamp now, @Param("until") Timestamp until);
    int advancePolling(@Param("botId") long botId, @Param("owner") String owner,
                       @Param("nextOffset") long nextOffset, @Param("now") Timestamp now, @Param("until") Timestamp until);
    int releasePolling(@Param("botId") long botId, @Param("owner") String owner);
}
