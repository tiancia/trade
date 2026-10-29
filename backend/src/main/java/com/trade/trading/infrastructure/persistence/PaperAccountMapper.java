package com.trade.trading.infrastructure.persistence;

import com.trade.trading.domain.paper.PaperEquityPoint;
import com.trade.trading.domain.paper.PaperFill;
import com.trade.trading.domain.paper.PaperDailyPerformance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

@Mapper
public interface PaperAccountMapper {
    int insertHeadIfAbsent(String instId);
    /** Returns the instrument key, including when the head has no active session. */
    String lockHead(String instId);
    int updateHead(@Param("instId") String instId, @Param("sessionId") String sessionId);
    PaperAccountRow active(String instId);
    PaperAccountRow lockActive(String instId);
    PaperAccountRow session(@Param("instId") String instId, @Param("sessionId") String sessionId);
    PaperAccountRow lockSession(@Param("instId") String instId, @Param("sessionId") String sessionId);
    int insertSession(PaperAccountRow row);
    int updateSession(PaperAccountRow row);
    int advanceVersion(@Param("sessionId") String sessionId, @Param("expectedVersion") long expectedVersion);
    int archive(String sessionId);
    int deleteSession(@Param("instId") String instId, @Param("sessionId") String sessionId, @Param("expectedVersion") long expectedVersion);
    int deleteFills(String sessionId);
    int deleteEquity(String sessionId);
    int updateRisk(@Param("sessionId") String sessionId, @Param("json") String json);
    PaperFill findFill(String decisionId);
    int insertFill(PaperFill fill);
    int insertEquity(PaperEquityPoint point);
    PaperEquityPoint lastMark(String sessionId);
    List<PaperAccountRow> sessions(@Param("instId") String instId, @Param("beforeId") Long beforeId, @Param("limit") int limit);
    List<PaperFill> fills(@Param("instId") String instId, @Param("sessionId") String sessionId,
                          @Param("beforeId") Long beforeId, @Param("limit") int limit);
    PaperDailyPerformance dailyPerformance(@Param("instId") String instId, @Param("sessionId") String sessionId,
                                            @Param("throughVersion") long throughVersion,
                                            @Param("from") Instant fromInclusive, @Param("to") Instant toExclusive);
    List<PaperEquityPoint> equity(@Param("instId") String instId, @Param("sessionId") String sessionId,
                                 @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
