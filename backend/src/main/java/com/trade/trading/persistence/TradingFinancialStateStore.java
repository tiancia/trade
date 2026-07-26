package com.trade.trading.persistence;

import com.trade.trading.model.TradingPositionState;
import com.trade.trading.model.TradingRiskState;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Authoritative persistence boundary for capital-bearing trading state.
 *
 * <p>Production uses the MySQL implementation. File-backed strategy memory is
 * deliberately outside this contract so a process-local JSON file can never
 * become the authority for position, cost, or risk decisions.</p>
 */
public interface TradingFinancialStateStore {
    TradingPositionState getOrCreatePosition(
            String accountScope,
            String instId,
            BigDecimal seedQuantity,
            BigDecimal seedAverageCost
    );

    TradingPositionState recordBuy(
            String accountScope,
            String instId,
            BigDecimal quantity,
            BigDecimal averageCost
    );

    TradingPositionState recordSell(
            String accountScope,
            String instId,
            BigDecimal quantity
    );

    TradingPositionState recordExchangePosition(
            String accountScope,
            String instId,
            BigDecimal exchangeQuantity,
            BigDecimal authoritativeQuantity,
            BigDecimal authoritativeAverageCost,
            Instant reconciledAt
    );

    TradingRiskState getOrCreateRiskState(String accountScope, TradingRiskState seed);

    TradingRiskState saveRiskState(String accountScope, TradingRiskState riskState);

    /**
     * Clears only reconciliation health fields without overwriting concurrent
     * risk-control updates in the same account row.
     */
    TradingRiskState recordReconciliationSuccess(String accountScope, Instant reconciledAt);

    /**
     * Atomically increments the durable reconciliation failure counter.
     */
    TradingRiskState recordReconciliationFailure(
            String accountScope,
            Instant reconciledAt,
            String error
    );

    SpotFillApplication applyCumulativeSpotFill(
            long orderId,
            String accountScope,
            String instId,
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
}
