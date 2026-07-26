package com.trade.trading.persistence;

import com.trade.trading.model.TradingPositionState;
import com.trade.trading.model.TradingRiskState;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * MySQL-backed financial state store.
 *
 * <p>Position updates and cumulative fill checkpoints are locked and committed
 * in one transaction. Replaying the same exchange snapshot therefore cannot
 * add the same fill to the position twice.</p>
 */
@Component
public class MyBatisTradingFinancialStateStore implements TradingFinancialStateStore {
    private static final String NET_POSITION = "net";

    private final TradingFinancialStateMapper mapper;

    public MyBatisTradingFinancialStateStore(TradingFinancialStateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public TradingPositionState getOrCreatePosition(
            String accountScope,
            String instId,
            BigDecimal seedQuantity,
            BigDecimal seedAverageCost
    ) {
        insertPositionIfAbsent(accountScope, instId, seedQuantity, seedAverageCost);
        TradingPositionRow row = mapper.findPosition(accountScope, instId);
        if (row == null) {
            throw new IllegalStateException("Trading position disappeared after initialization");
        }
        return toPositionState(row);
    }

    @Override
    @Transactional
    public TradingPositionState recordBuy(
            String accountScope,
            String instId,
            BigDecimal quantity,
            BigDecimal averageCost
    ) {
        requirePositive(quantity, "buy quantity");
        requirePositive(averageCost, "buy average cost");
        TradingPositionRow row = lockPosition(accountScope, instId);
        applyBuy(row, quantity, quantity.multiply(averageCost));
        updatePosition(row);
        return toPositionState(row);
    }

    @Override
    @Transactional
    public TradingPositionState recordSell(
            String accountScope,
            String instId,
            BigDecimal quantity
    ) {
        requirePositive(quantity, "sell quantity");
        TradingPositionRow row = lockPosition(accountScope, instId);
        applySell(row, quantity, false);
        updatePosition(row);
        return toPositionState(row);
    }

    @Override
    @Transactional
    public TradingPositionState recordExchangePosition(
            String accountScope,
            String instId,
            BigDecimal exchangeQuantity,
            BigDecimal authoritativeQuantity,
            BigDecimal authoritativeAverageCost,
            Instant reconciledAt
    ) {
        TradingPositionRow row = lockPosition(accountScope, instId);
        row.setExchangeQuantity(zeroIfNull(exchangeQuantity))
                .setLastReconciledAt(reconciledAt == null ? Instant.now() : reconciledAt);
        if (authoritativeQuantity != null) {
            BigDecimal normalizedQuantity = authoritativeQuantity.max(BigDecimal.ZERO);
            row.setQuantity(normalizedQuantity);
            if (normalizedQuantity.signum() == 0) {
                row.setAverageCost(BigDecimal.ZERO);
            } else if (authoritativeAverageCost != null && authoritativeAverageCost.signum() > 0) {
                row.setAverageCost(authoritativeAverageCost);
            }
        }
        updatePosition(row);
        return toPositionState(row);
    }

    @Override
    @Transactional
    public TradingRiskState getOrCreateRiskState(String accountScope, TradingRiskState seed) {
        insertRiskIfAbsent(accountScope, seed);
        TradingRiskStateRow row = mapper.findRiskState(accountScope);
        if (row == null) {
            throw new IllegalStateException("Trading risk state disappeared after initialization");
        }
        return toRiskState(row);
    }

    @Override
    @Transactional
    public TradingRiskState saveRiskState(String accountScope, TradingRiskState riskState) {
        insertRiskIfAbsent(accountScope, riskState);
        TradingRiskStateRow row = mapper.lockRiskState(accountScope);
        if (row == null) {
            throw new IllegalStateException("Trading risk state cannot be locked: " + accountScope);
        }
        copyRiskControlState(riskState, row);
        row.setVersion(row.getVersion() + 1)
                .setUpdatedAt(Instant.now());
        if (mapper.updateRiskState(row) != 1) {
            throw new IllegalStateException("Trading risk state update lost: " + accountScope);
        }
        return toRiskState(row);
    }

    @Override
    @Transactional
    public TradingRiskState recordReconciliationSuccess(String accountScope, Instant reconciledAt) {
        insertRiskIfAbsent(accountScope, null);
        Instant now = Instant.now();
        if (mapper.recordReconciliationSuccess(
                accountScope,
                reconciledAt == null ? now : reconciledAt,
                now
        ) != 1) {
            throw new IllegalStateException("Trading reconciliation state update lost: " + accountScope);
        }
        return requiredRiskState(accountScope);
    }

    @Override
    @Transactional
    public TradingRiskState recordReconciliationFailure(
            String accountScope,
            Instant reconciledAt,
            String error
    ) {
        insertRiskIfAbsent(accountScope, null);
        Instant now = Instant.now();
        if (mapper.recordReconciliationFailure(
                accountScope,
                reconciledAt == null ? now : reconciledAt,
                error,
                now
        ) != 1) {
            throw new IllegalStateException("Trading reconciliation failure update lost: " + accountScope);
        }
        return requiredRiskState(accountScope);
    }

    @Override
    @Transactional
    public SpotFillApplication applyCumulativeSpotFill(
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
        BigDecimal observedFill = zeroIfNull(cumulativeFilledSize);
        BigDecimal observedPosition = zeroIfNull(cumulativePositionQuantity);
        BigDecimal observedQuoteCost = zeroIfNull(cumulativeQuoteCost);
        if (observedFill.signum() <= 0 || observedPosition.signum() <= 0) {
            return SpotFillApplication.unchanged();
        }
        String normalizedSide = normalizeSide(side);

        OrderFillLedgerRow seed = new OrderFillLedgerRow()
                .setOrderId(orderId)
                .setSide(normalizedSide)
                .setCumulativeFilledSize(BigDecimal.ZERO)
                .setAppliedPositionQuantity(BigDecimal.ZERO)
                .setAppliedQuoteCost(BigDecimal.ZERO)
                .setVersion(0)
                .setCreatedAt(Instant.now())
                .setUpdatedAt(Instant.now());
        mapper.insertFillLedgerIfAbsent(seed);
        OrderFillLedgerRow ledger = mapper.lockFillLedger(orderId);
        if (ledger == null) {
            throw new IllegalStateException("Order fill ledger cannot be locked: " + orderId);
        }
        if (!normalizedSide.equalsIgnoreCase(ledger.getSide())) {
            throw new IllegalStateException("Order side changed during reconciliation: orderId=" + orderId);
        }

        BigDecimal appliedFill = zeroIfNull(ledger.getCumulativeFilledSize());
        BigDecimal appliedPosition = zeroIfNull(ledger.getAppliedPositionQuantity());
        BigDecimal appliedQuoteCost = zeroIfNull(ledger.getAppliedQuoteCost());
        // Older WebSocket or REST observations are normal. They must never
        // reverse a newer cumulative checkpoint.
        if (observedFill.compareTo(appliedFill) < 0
                || observedPosition.compareTo(appliedPosition) < 0
                || observedQuoteCost.compareTo(appliedQuoteCost) < 0) {
            return SpotFillApplication.unchanged();
        }

        BigDecimal positionDelta = observedPosition.subtract(appliedPosition);
        BigDecimal quoteCostDelta = observedQuoteCost.subtract(appliedQuoteCost);
        if (positionDelta.signum() == 0 && quoteCostDelta.signum() == 0) {
            return SpotFillApplication.unchanged();
        }

        TradingPositionRow position = lockPosition(accountScope, instId);
        boolean firstApplication = appliedPosition.signum() == 0;
        if ("buy".equals(normalizedSide)) {
            if (positionDelta.signum() <= 0 || quoteCostDelta.signum() <= 0) {
                throw new IllegalStateException("BUY cumulative fill did not advance monotonically");
            }
            applyBuy(position, positionDelta, quoteCostDelta);
        } else {
            if (positionDelta.signum() <= 0) {
                throw new IllegalStateException("SELL cumulative fill did not advance monotonically");
            }
            // A live ledger may not reduce more managed quantity than it owns.
            // Failing the transaction is safer than silently hiding a drift.
            applySell(position, positionDelta, true);
        }
        updatePosition(position);

        ledger.setCumulativeFilledSize(observedFill)
                .setAppliedPositionQuantity(observedPosition)
                .setAppliedQuoteCost(observedQuoteCost)
                .setAverageFillPrice(zeroIfNull(averageFillPrice))
                .setFee(zeroIfNull(fee))
                .setFeeCcy(feeCcy)
                .setExchangeState(exchangeState)
                .setExchangeUpdatedAt(exchangeUpdatedAt)
                .setVersion(ledger.getVersion() + 1)
                .setUpdatedAt(Instant.now());
        if (mapper.updateFillLedger(ledger) != 1) {
            throw new IllegalStateException("Order fill ledger update lost: " + orderId);
        }
        return new SpotFillApplication(true, firstApplication, positionDelta);
    }

    private TradingPositionRow lockPosition(String accountScope, String instId) {
        insertPositionIfAbsent(accountScope, instId, BigDecimal.ZERO, BigDecimal.ZERO);
        TradingPositionRow row = mapper.lockPosition(accountScope, instId);
        if (row == null) {
            throw new IllegalStateException("Trading position cannot be locked: " + accountScope + "/" + instId);
        }
        return row;
    }

    private void insertPositionIfAbsent(
            String accountScope,
            String instId,
            BigDecimal seedQuantity,
            BigDecimal seedAverageCost
    ) {
        BigDecimal quantity = zeroIfNull(seedQuantity).max(BigDecimal.ZERO);
        BigDecimal averageCost = quantity.signum() == 0
                ? BigDecimal.ZERO
                : zeroIfNull(seedAverageCost).max(BigDecimal.ZERO);
        Instant now = Instant.now();
        mapper.insertPositionIfAbsent(new TradingPositionRow()
                .setAccountScope(accountScope)
                .setInstId(instId)
                .setPositionSide(NET_POSITION)
                .setQuantity(quantity)
                .setAverageCost(averageCost)
                .setVersion(0)
                .setCreatedAt(now)
                .setUpdatedAt(now));
    }

    private void insertRiskIfAbsent(String accountScope, TradingRiskState seed) {
        TradingRiskStateRow row = new TradingRiskStateRow()
                .setAccountScope(accountScope)
                .setVersion(0)
                .setCreatedAt(Instant.now())
                .setUpdatedAt(Instant.now());
        copyRiskControlState(seed, row);
        TradingRiskState safe = seed == null ? new TradingRiskState() : seed;
        row.setConsecutiveReconciliationFailures(safe.getConsecutiveReconciliationFailures())
                .setLastReconciliationAt(parseInstant(safe.getLastReconciliationAt()))
                .setLastReconciliationError(safe.getLastReconciliationError());
        mapper.insertRiskStateIfAbsent(row);
    }

    private void updatePosition(TradingPositionRow row) {
        row.setVersion(row.getVersion() + 1)
                .setUpdatedAt(Instant.now());
        if (mapper.updatePosition(row) != 1) {
            throw new IllegalStateException(
                    "Trading position update lost: " + row.getAccountScope() + "/" + row.getInstId()
            );
        }
    }

    private static void applyBuy(
            TradingPositionRow row,
            BigDecimal quantityDelta,
            BigDecimal quoteCostDelta
    ) {
        BigDecimal oldQuantity = zeroIfNull(row.getQuantity());
        BigDecimal oldCost = zeroIfNull(row.getAverageCost());
        BigDecimal newQuantity = oldQuantity.add(quantityDelta);
        BigDecimal totalCost = oldQuantity.multiply(oldCost).add(quoteCostDelta);
        row.setQuantity(newQuantity)
                .setAverageCost(totalCost.divide(newQuantity, 18, RoundingMode.HALF_UP));
    }

    private static void applySell(TradingPositionRow row, BigDecimal quantityDelta, boolean strict) {
        BigDecimal oldQuantity = zeroIfNull(row.getQuantity());
        if (strict && quantityDelta.compareTo(oldQuantity) > 0) {
            throw new IllegalStateException(
                    "Reconciled SELL exceeds managed position: sell=" + quantityDelta + ", managed=" + oldQuantity
            );
        }
        BigDecimal remaining = oldQuantity.subtract(quantityDelta).max(BigDecimal.ZERO);
        row.setQuantity(remaining)
                .setAverageCost(remaining.signum() == 0 ? BigDecimal.ZERO : zeroIfNull(row.getAverageCost()));
    }

    private static TradingPositionState toPositionState(TradingPositionRow row) {
        return new TradingPositionState()
                .setAccountScope(row.getAccountScope())
                .setInstId(row.getInstId())
                .setPositionSide(row.getPositionSide())
                .setQuantity(zeroIfNull(row.getQuantity()))
                .setAverageCost(zeroIfNull(row.getAverageCost()))
                .setExchangeQuantity(row.getExchangeQuantity())
                .setLastReconciledAt(row.getLastReconciledAt())
                .setVersion(row.getVersion());
    }

    private TradingRiskState requiredRiskState(String accountScope) {
        TradingRiskStateRow row = mapper.findRiskState(accountScope);
        if (row == null) {
            throw new IllegalStateException("Trading risk state not found: " + accountScope);
        }
        return toRiskState(row);
    }

    private static void copyRiskControlState(TradingRiskState source, TradingRiskStateRow target) {
        TradingRiskState safe = source == null ? new TradingRiskState() : source;
        target.setCurrentEquity(zeroIfNull(safe.getCurrentEquity()))
                .setEquityHighWatermark(zeroIfNull(safe.getEquityHighWatermark()))
                .setDayStartEquity(zeroIfNull(safe.getDayStartEquity()))
                .setDayStartDate(safe.getDayStartDate())
                .setConsecutiveLosses(safe.getConsecutiveLosses())
                .setLossCooldownUntil(parseInstant(safe.getLossCooldownUntil()))
                .setLastTradeTime(parseInstant(safe.getLastTradeTime()))
                .setConsecutiveOpenActions(safe.getConsecutiveOpenActions())
                .setLastRiskReason(safe.getLastRiskReason());
    }

    private static TradingRiskState toRiskState(TradingRiskStateRow row) {
        return new TradingRiskState()
                .setCurrentEquity(zeroIfNull(row.getCurrentEquity()))
                .setEquityHighWatermark(zeroIfNull(row.getEquityHighWatermark()))
                .setDayStartEquity(zeroIfNull(row.getDayStartEquity()))
                .setDayStartDate(row.getDayStartDate())
                .setConsecutiveLosses(row.getConsecutiveLosses())
                .setLossCooldownUntil(formatInstant(row.getLossCooldownUntil()))
                .setLastTradeTime(formatInstant(row.getLastTradeTime()))
                .setConsecutiveOpenActions(row.getConsecutiveOpenActions())
                .setLastRiskReason(row.getLastRiskReason())
                .setConsecutiveReconciliationFailures(row.getConsecutiveReconciliationFailures())
                .setLastReconciliationAt(formatInstant(row.getLastReconciliationAt()))
                .setLastReconciliationError(row.getLastReconciliationError());
    }

    private static String normalizeSide(String side) {
        if ("buy".equalsIgnoreCase(side)) {
            return "buy";
        }
        if ("sell".equalsIgnoreCase(side)) {
            return "sell";
        }
        throw new IllegalArgumentException("Unsupported spot order side: " + side);
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private static String formatInstant(Instant value) {
        return value == null ? null : value.toString();
    }
}
