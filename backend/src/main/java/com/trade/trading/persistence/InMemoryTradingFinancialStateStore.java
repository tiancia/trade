package com.trade.trading.persistence;

import com.trade.trading.model.TradingPositionState;
import com.trade.trading.model.TradingRiskState;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                new TradingPositionState()
                        .setAccountScope(accountScope)
                        .setInstId(instId)
                        .setQuantity(zero(seedQuantity))
                        .setAverageCost(zero(seedAverageCost))));
    }

    @Override
    public synchronized TradingPositionState recordBuy(
            String accountScope,
            String instId,
            BigDecimal quantity,
            BigDecimal averageCost
    ) {
        TradingPositionState position = mutablePosition(accountScope, instId);
        BigDecimal oldQuantity = zero(position.getQuantity());
        BigDecimal newQuantity = oldQuantity.add(quantity);
        BigDecimal totalCost = oldQuantity.multiply(zero(position.getAverageCost()))
                .add(quantity.multiply(averageCost));
        position.setQuantity(newQuantity)
                .setAverageCost(totalCost.divide(newQuantity, 18, RoundingMode.HALF_UP))
                .setVersion(position.getVersion() + 1);
        return copyPosition(position);
    }

    @Override
    public synchronized TradingPositionState recordSell(
            String accountScope,
            String instId,
            BigDecimal quantity
    ) {
        TradingPositionState position = mutablePosition(accountScope, instId);
        BigDecimal remaining = zero(position.getQuantity()).subtract(quantity).max(BigDecimal.ZERO);
        position.setQuantity(remaining)
                .setAverageCost(remaining.signum() == 0 ? BigDecimal.ZERO : zero(position.getAverageCost()))
                .setVersion(position.getVersion() + 1);
        return copyPosition(position);
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
        TradingPositionState position = mutablePosition(accountScope, instId);
        position.setExchangeQuantity(zero(exchangeQuantity))
                .setLastReconciledAt(reconciledAt == null ? Instant.now() : reconciledAt)
                .setVersion(position.getVersion() + 1);
        if (authoritativeQuantity != null) {
            BigDecimal normalizedQuantity = authoritativeQuantity.max(BigDecimal.ZERO);
            position.setQuantity(normalizedQuantity);
            if (normalizedQuantity.signum() == 0) {
                position.setAverageCost(BigDecimal.ZERO);
            } else if (authoritativeAverageCost != null && authoritativeAverageCost.signum() > 0) {
                position.setAverageCost(authoritativeAverageCost);
            }
        }
        return copyPosition(position);
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
        if (observedFill.compareTo(previous.filled()) < 0
                || observedPosition.compareTo(previous.position()) < 0
                || observedCost.compareTo(previous.quoteCost()) < 0) {
            return SpotFillApplication.unchanged();
        }
        BigDecimal delta = observedPosition.subtract(previous.position());
        BigDecimal costDelta = observedCost.subtract(previous.quoteCost());
        if (delta.signum() == 0 && costDelta.signum() == 0) {
            return SpotFillApplication.unchanged();
        }
        TradingPositionState position = mutablePosition(accountScope, instId);
        if ("buy".equalsIgnoreCase(side)) {
            BigDecimal oldQuantity = zero(position.getQuantity());
            BigDecimal newQuantity = oldQuantity.add(delta);
            BigDecimal totalCost = oldQuantity.multiply(zero(position.getAverageCost())).add(costDelta);
            position.setQuantity(newQuantity)
                    .setAverageCost(totalCost.divide(newQuantity, 18, RoundingMode.HALF_UP));
        } else {
            if (delta.compareTo(zero(position.getQuantity())) > 0) {
                throw new IllegalStateException("Reconciled SELL exceeds managed position");
            }
            BigDecimal remaining = zero(position.getQuantity()).subtract(delta);
            position.setQuantity(remaining)
                    .setAverageCost(remaining.signum() == 0 ? BigDecimal.ZERO : position.getAverageCost());
        }
        position.setVersion(position.getVersion() + 1);
        fills.put(orderId, new FillCheckpoint(side, observedFill, observedPosition, observedCost));
        return new SpotFillApplication(true, previous.position().signum() == 0, delta);
    }

    private TradingPositionState mutablePosition(String accountScope, String instId) {
        return positions.computeIfAbsent(key(accountScope, instId), ignored ->
                new TradingPositionState()
                        .setAccountScope(accountScope)
                        .setInstId(instId));
    }

    private static String key(String accountScope, String instId) {
        return accountScope + "|" + instId;
    }

    private static TradingPositionState copyPosition(TradingPositionState source) {
        return new TradingPositionState()
                .setAccountScope(source.getAccountScope())
                .setInstId(source.getInstId())
                .setPositionSide(source.getPositionSide())
                .setQuantity(zero(source.getQuantity()))
                .setAverageCost(zero(source.getAverageCost()))
                .setExchangeQuantity(source.getExchangeQuantity())
                .setLastReconciledAt(source.getLastReconciledAt())
                .setVersion(source.getVersion());
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
