package com.trade.trading.infrastructure.persistence;

import com.trade.trading.domain.order.PositionAccounting;
import com.trade.trading.application.port.TradingFinancialStateStore;
import com.trade.trading.domain.model.TradingPositionState;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.order.SpotFillApplication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * In-process test adapter for {@link TradingFinancialStateStore}.
 *
 * <p>Spring never selects this adapter; production construction injects the
 * MyBatis implementation. It keeps focused unit tests independent of MySQL
 * while preserving cumulative-fill idempotency semantics.</p>
 */
public class InMemoryTradingFinancialStateStore implements TradingFinancialStateStore {
    private final Map<String, TradingPositionState> positions = new HashMap<>();
    private final Map<String, TradingRiskState> risks = new HashMap<>();
    private final Map<Long, FillCheckpoint> fills = new HashMap<>();

    @Override
    public synchronized TradingPositionState getOrCreatePosition(
            String accountScope,
            String instId,
            BigDecimal seedQuantity,
            BigDecimal seedAverageCost
    ) {
        return copyPosition(positions.computeIfAbsent(key(accountScope, instId), ignored ->
                TradingPositionState.seed(accountScope, instId, seedQuantity, seedAverageCost)));
    }

    @Override
    public synchronized TradingPositionState recordBuy(
            String accountScope,
            String instId,
            BigDecimal quantity,
            BigDecimal averageCost
    ) {
        TradingPositionState position = currentPosition(accountScope, instId);
        var next = position.buy(quantity, quantity.multiply(averageCost));
        positions.put(key(accountScope, instId), next);
        return next;
    }

    @Override
    public synchronized TradingPositionState recordSell(
            String accountScope,
            String instId,
            BigDecimal quantity
    ) {
        TradingPositionState position = currentPosition(accountScope, instId);
        var next = position.sell(quantity);
        positions.put(key(accountScope, instId), next);
        return next;
    }

    @Override
    public synchronized TradingPositionState recordExchangePosition(
            String accountScope,
            String instId,
            BigDecimal exchangeQuantity,
            BigDecimal authoritativeQuantity,
            BigDecimal authoritativeAverageCost,
            Instant reconciledAt
    ) {
        TradingPositionState position = currentPosition(accountScope, instId);
        var next = position.reconcile(exchangeQuantity, authoritativeQuantity, authoritativeAverageCost,
                reconciledAt == null ? Instant.now() : reconciledAt);
        positions.put(key(accountScope, instId), next);
        return next;
    }

    @Override
    public synchronized TradingRiskState getOrCreateRiskState(String accountScope, TradingRiskState seed) {
        return copyRisk(risks.computeIfAbsent(accountScope, ignored -> copyRisk(seed)));
    }

    @Override
    public synchronized TradingRiskState saveRiskState(String accountScope, TradingRiskState riskState) {
        TradingRiskState copy = copyRisk(riskState);
        TradingRiskState current = risks.get(accountScope);
        if (current != null) {
            copy.setConsecutiveReconciliationFailures(current.getConsecutiveReconciliationFailures())
                    .setLastReconciliationAt(current.getLastReconciliationAt())
                    .setLastReconciliationError(current.getLastReconciliationError());
        }
        risks.put(accountScope, copy);
        return copyRisk(copy);
    }

    @Override
    public synchronized TradingRiskState recordReconciliationSuccess(String accountScope, Instant reconciledAt) {
        TradingRiskState risk = risks.computeIfAbsent(accountScope, ignored -> new TradingRiskState());
        risk.setConsecutiveReconciliationFailures(0)
                .setLastReconciliationAt((reconciledAt == null ? Instant.now() : reconciledAt).toString())
                .setLastReconciliationError(null);
        return copyRisk(risk);
    }

    @Override
    public synchronized TradingRiskState recordReconciliationFailure(
            String accountScope,
            Instant reconciledAt,
            String error
    ) {
        TradingRiskState risk = risks.computeIfAbsent(accountScope, ignored -> new TradingRiskState());
        risk.setConsecutiveReconciliationFailures(risk.getConsecutiveReconciliationFailures() + 1)
                .setLastReconciliationAt((reconciledAt == null ? Instant.now() : reconciledAt).toString())
                .setLastReconciliationError(error);
        return copyRisk(risk);
    }

    @Override
    public synchronized SpotFillApplication applyCumulativeSpotFill(
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
    ) {
        FillCheckpoint previous = fills.getOrDefault(
                orderId,
                new FillCheckpoint(side, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        );
        BigDecimal observedFill = zero(cumulativeFilledSize);
        BigDecimal observedPosition = zero(cumulativePositionQuantity);
        BigDecimal observedCost = zero(cumulativeQuoteCost);
        var change = PositionAccounting.cumulativeDelta(observedFill, observedPosition, observedCost,
                previous.filled(), previous.position(), previous.quoteCost());
        if (!change.changed()) { return SpotFillApplication.unchanged(); }
        BigDecimal delta = change.quantity();
        BigDecimal costDelta = change.quoteCost();
        TradingPositionState position = currentPosition(accountScope, instId);
        var next = "buy".equalsIgnoreCase(side) ? position.buy(delta, costDelta) : position.applySellFill(delta);
        positions.put(key(accountScope, instId), next);
        fills.put(orderId, new FillCheckpoint(side, observedFill, observedPosition, observedCost));
        return new SpotFillApplication(true, previous.position().signum() == 0, delta);
    }

    private TradingPositionState currentPosition(String accountScope, String instId) {
        return positions.computeIfAbsent(key(accountScope, instId), ignored ->
                TradingPositionState.seed(accountScope, instId, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private static String key(String accountScope, String instId) {
        return accountScope + "|" + instId;
    }

    private static TradingPositionState copyPosition(TradingPositionState source) {
        return source; // Immutable snapshot.
    }

    private static TradingRiskState copyRisk(TradingRiskState source) {
        TradingRiskState safe = source == null ? new TradingRiskState() : source;
        return new TradingRiskState()
                .setCurrentEquity(zero(safe.getCurrentEquity()))
                .setEquityHighWatermark(zero(safe.getEquityHighWatermark()))
                .setDayStartEquity(zero(safe.getDayStartEquity()))
                .setDayStartDate(safe.getDayStartDate())
                .setConsecutiveLosses(safe.getConsecutiveLosses())
                .setLossCooldownUntil(safe.getLossCooldownUntil())
                .setLastTradeTime(safe.getLastTradeTime())
                .setConsecutiveOpenActions(safe.getConsecutiveOpenActions())
                .setLastRiskReason(safe.getLastRiskReason())
                .setConsecutiveReconciliationFailures(safe.getConsecutiveReconciliationFailures())
                .setLastReconciliationAt(safe.getLastReconciliationAt())
                .setLastReconciliationError(safe.getLastReconciliationError());
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record FillCheckpoint(
            String side,
            BigDecimal filled,
            BigDecimal position,
            BigDecimal quoteCost
    ) {
    }
}
