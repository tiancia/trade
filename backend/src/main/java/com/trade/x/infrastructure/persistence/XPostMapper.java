package com.trade.x.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.util.List;

@Mapper
public interface XPostMapper {
    void ensureAccountGate(@Param("userId") String userId);
    String lockAccountGate(@Param("userId") String userId);
    int countGenerated(@Param("userId") String userId, @Param("since") Timestamp since);
    int countAttempts(@Param("userId") String userId, @Param("since") Timestamp since);
    Timestamp lastAttempt(@Param("userId") String userId);
    XPostRow findByGenerationKey(@Param("userId") String userId, @Param("generationKey") String generationKey);
    void insert(XPostRow row);
    XPostRow find(@Param("id") String id);
    List<XPostRow> recent(@Param("limit") int limit);
    List<XPostRow> actionable(@Param("limit") int limit);
    List<XPostRow> history(@Param("id") String id);
    int update(@Param("row") XPostRow row, @Param("expectedRevision") long expectedRevision);
    void insertHistory(XPostRow row);
    void insertAttempt(XPostRow row);
    void finishAttempt(XPostRow row);
}
