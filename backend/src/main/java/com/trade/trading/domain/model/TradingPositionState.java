package com.trade.trading.domain.model;

import com.trade.trading.domain.order.PositionAccounting;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable managed position; exchange observations cannot silently overwrite ownership. */
@Getter
@EqualsAndHashCode
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class TradingPositionState {
    private final String accountScope;
    private final String instId;
    private final String positionSide;
    private final BigDecimal quantity;
    private final BigDecimal averageCost;
    private final BigDecimal exchangeQuantity;
    private final Instant lastReconciledAt;
    private final long version;

    public static TradingPositionState seed(String accountScope, String instId, BigDecimal quantity, BigDecimal averageCost) {
        BigDecimal normalized = zero(quantity).max(BigDecimal.ZERO);
        return new TradingPositionState(accountScope, instId, "net", normalized,
                normalized.signum() == 0 ? BigDecimal.ZERO : zero(averageCost).max(BigDecimal.ZERO), null, null, 0);
    }

    /** Restore an existing persisted snapshot without advancing its revision. */
    public static TradingPositionState restore(String accountScope, String instId, String positionSide,
            BigDecimal quantity, BigDecimal averageCost, BigDecimal exchangeQuantity, Instant reconciledAt, long version) {
        return new TradingPositionState(accountScope, instId, positionSide, zero(quantity), zero(averageCost),
                exchangeQuantity, reconciledAt, version);
    }

    public TradingPositionState buy(BigDecimal quantityDelta, BigDecimal quoteCostDelta) {
        PositionAccounting.requireMonotonicDelta("buy", quantityDelta, quoteCostDelta);
        return withPosition(PositionAccounting.buy(quantity, averageCost, quantityDelta, quoteCostDelta));
    }

    /** Manual/paper sells retain the existing clamp-to-zero behavior. */
    public TradingPositionState sell(BigDecimal quantityDelta) {
        return sell(quantityDelta, false);
    }

    /** A durable exchange fill must never consume more than the managed position. */
    public TradingPositionState applySellFill(BigDecimal quantityDelta) {
        return sell(quantityDelta, true);
    }

    private TradingPositionState sell(BigDecimal quantityDelta, boolean strict) {
        PositionAccounting.requireMonotonicDelta("sell", quantityDelta, BigDecimal.ZERO);
        return withPosition(PositionAccounting.sell(quantity, averageCost, quantityDelta, strict));
    }

    public TradingPositionState reconcile(BigDecimal observedQuantity, BigDecimal authoritativeQuantity,
            BigDecimal authoritativeAverageCost, Instant now) {
        BigDecimal nextQuantity = quantity;
        BigDecimal nextCost = averageCost;
        if (authoritativeQuantity != null) {
            nextQuantity = authoritativeQuantity.max(BigDecimal.ZERO);
            if (nextQuantity.signum() == 0) { nextCost = BigDecimal.ZERO; }
            else if (authoritativeAverageCost != null && authoritativeAverageCost.signum() > 0) {
                nextCost = authoritativeAverageCost;
            }
        }
        return new TradingPositionState(accountScope, instId, positionSide, nextQuantity, nextCost,
                zero(observedQuantity), now, version + 1);
    }

    private TradingPositionState withPosition(PositionAccounting.Position position) {
        return new TradingPositionState(accountScope, instId, positionSide, position.quantity(), position.averageCost(),
                exchangeQuantity, lastReconciledAt, version + 1);
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
