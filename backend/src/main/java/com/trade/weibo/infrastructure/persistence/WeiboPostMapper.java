package com.trade.weibo.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.util.List;

@Mapper
public interface WeiboPostMapper {
    void ensureAccountGate(@Param("uid") String uid);
    String lockAccountGate(@Param("uid") String uid);
    int countGenerated(@Param("uid") String uid, @Param("since") Timestamp since);
    int countAttempts(@Param("uid") String uid, @Param("since") Timestamp since);
    Timestamp lastAttempt(@Param("uid") String uid);
    WeiboPostRow findByEvent(@Param("uid") String uid, @Param("eventKey") String eventKey);
    void insert(WeiboPostRow row);
    WeiboPostRow find(@Param("id") String id);
    List<WeiboPostRow> recent(@Param("limit") int limit);
    List<WeiboPostRow> actionable(@Param("limit") int limit);
    List<WeiboPostRow> history(@Param("id") String id);
    int update(@Param("row") WeiboPostRow row, @Param("expectedRevision") long expectedRevision);
    void insertHistory(WeiboPostRow row);
    void insertAttempt(WeiboPostRow row);
    void finishAttempt(WeiboPostRow row);
}
