package com.trade.trading.application.port;

import com.trade.trading.domain.paper.PaperAccount;
import com.trade.trading.domain.paper.PaperEquityPoint;
import com.trade.trading.domain.paper.PaperFill;
import com.trade.trading.domain.model.TradingRiskState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/** Serializes session replacement, cash/position changes, idempotent fills, and equity samples. */
public interface PaperAccountStore {
    PaperAccount active(String instId);
    PaperAccount session(String instId, String sessionId);
    boolean changeStrategyWhileFlat(String instId, String sessionId, long expectedVersion,
                                    java.util.function.BooleanSupplier action);
    PaperAccount start(String instId, String quoteCcy, BigDecimal initialCash,
                       String expectedSessionId, Long expectedVersion, Instant now);
    void delete(String instId, String sessionId, long expectedVersion);
    PaperAccount mark(String instId, String expectedSessionId, BigDecimal price, Instant marketTime, String strategyId, Long strategyConfigVersion, Instant now);
    AppliedFill fill(FillCommand command);
    List<PaperAccount> sessions(String instId, Long beforeId, int limit);
    List<PaperFill> fills(String instId, String sessionId, Long beforeId, int limit);
    List<PaperEquityPoint> equity(String instId, String sessionId, Long beforeId, int limit);
    TradingRiskState risk(String instId, String sessionId);
    TradingRiskState updateRisk(String instId, String sessionId, long expectedAccountVersion,
                                UnaryOperator<TradingRiskState> transition);

    record FillCommand(String instId, String expectedSessionId, long expectedVersion, String decisionId,
                       String strategyId, long strategyConfigVersion, String strategyConfigSnapshotJson,
                       String side, BigDecimal size, BigDecimal price, BigDecimal feeRate, Instant marketTime, Instant now) {}
    record AppliedFill(PaperFill fill, PaperAccount account, boolean replay) {}
}
