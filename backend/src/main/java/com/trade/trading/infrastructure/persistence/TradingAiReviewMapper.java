package com.trade.trading.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

@Mapper
public interface TradingAiReviewMapper {
    int insertGuard(@Param("scope") String scope);
    Instant lockGuard(@Param("scope") String scope);
    int advanceReview(@Param("scope") String scope, @Param("next") Instant next);
    int insert(TradingAiReviewRow row);
    int capture(@Param("id") String id, @Param("snapshot") String snapshot);
    int proposed(@Param("id") String id, @Param("snapshot") String snapshot, @Param("proposal") String proposal);
    int beginApplying(@Param("id") String id, @Param("now") Instant now);
    int complete(@Param("id") String id, @Param("outcome") String outcome,
                 @Param("detail") String detail, @Param("now") Instant now);
    TradingAiReviewRow find(@Param("id") String id);
    int reserveNotification(@Param("id") String id);
    int advanceNotification(@Param("scope") String scope, @Param("now") Instant now, @Param("next") Instant next);
    int notification(@Param("id") String id, @Param("status") String status);
    List<TradingAiReviewRow> recent(@Param("scope") String scope, @Param("limit") int limit);
}
