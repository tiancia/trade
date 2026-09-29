package com.trade.trading.application.port;

import com.trade.trading.domain.model.AiTradingDecision;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.model.TradingPositionState;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.order.SpotFillApplication;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Strategy memory and authoritative financial-state operations.
 * Implementations preserve cumulative-fill idempotency and revision checks.
 */
public interface TradingStateStore {
    TradingState getState();

    void recordBuy(BigDecimal baseAmount, BigDecimal price);

    void recordSell(BigDecimal baseAmount);

    TradingPositionState recordExchangePosition(
            BigDecimal exchangeQuantity,
            BigDecimal authoritativeQuantity,
            BigDecimal authoritativeAverageCost,
            Instant reconciledAt
    );

    SpotFillApplication applyCumulativeSpotFill(
            long orderId,
            String side,
            BigDecimal cumulativeFilledSize,
            BigDecimal cumulativePositionQuantity,
            BigDecimal cumulativeQuoteCost,
            BigDecimal averageFillPrice,
            BigDecimal fee,
            String feeCcy,
            String exchangeState,
            Instant exchangeUpdatedAt
    );

    void recordDecision(TradingDecisionRecord record, int limit);

    void recordStrategyState(String decisionId, AiTradingDecision decision);

    void recordRiskState(TradingRiskState riskState);

    TradingRiskState recordReconciliationSuccess(Instant reconciledAt);

    TradingRiskState recordReconciliationFailure(Instant reconciledAt, String error);

    TradingState selectActiveStrategy(String strategyId, Long expectedRevision);
}
