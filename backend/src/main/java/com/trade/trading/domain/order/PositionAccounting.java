package com.trade.trading.domain.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
/** Position cost and monotonic cumulative-fill arithmetic, independent of row locking. */
public final class PositionAccounting {
    public record Position(BigDecimal quantity, BigDecimal averageCost) {}
    public record Delta(boolean changed, BigDecimal quantity, BigDecimal quoteCost) {}
    public static Position buy(
            BigDecimal quantity, BigDecimal averageCost,
            BigDecimal quantityDelta,
            BigDecimal quoteCostDelta
    ) {
        BigDecimal oldQuantity = zeroIfNull(quantity);
        BigDecimal oldCost = zeroIfNull(averageCost);
        BigDecimal newQuantity = oldQuantity.add(quantityDelta);
        BigDecimal totalCost = oldQuantity.multiply(oldCost).add(quoteCostDelta);
        return new Position(newQuantity, totalCost.divide(newQuantity, 18, RoundingMode.HALF_UP));
    }
    public static Position sell(BigDecimal quantity, BigDecimal averageCost, BigDecimal quantityDelta, boolean strict) {
        BigDecimal oldQuantity = zeroIfNull(quantity);
        if (strict && quantityDelta.compareTo(oldQuantity) > 0) {
            throw new IllegalStateException(
                    "Reconciled SELL exceeds managed position: sell=" + quantityDelta + ", managed=" + oldQuantity
            );
        }
        BigDecimal remaining = oldQuantity.subtract(quantityDelta).max(BigDecimal.ZERO);
        return new Position(remaining, remaining.signum() == 0 ? BigDecimal.ZERO : zeroIfNull(averageCost));
    }
    public static Delta cumulativeDelta(BigDecimal observedFill, BigDecimal observedPosition, BigDecimal observedCost,
                                        BigDecimal appliedFill, BigDecimal appliedPosition, BigDecimal appliedCost) {
        if (observedFill.compareTo(appliedFill) < 0 || observedPosition.compareTo(appliedPosition) < 0
                || observedCost.compareTo(appliedCost) < 0) { return new Delta(false, BigDecimal.ZERO, BigDecimal.ZERO); }
        BigDecimal quantity = observedPosition.subtract(appliedPosition);
        BigDecimal cost = observedCost.subtract(appliedCost);
        return new Delta(quantity.signum() != 0 || cost.signum() != 0, quantity, cost);
    }
    public static void requireMonotonicDelta(String side, BigDecimal quantity, BigDecimal quoteCost) {
        if ("buy".equals(side) && (quantity.signum() <= 0 || quoteCost.signum() <= 0)) {
            throw new IllegalStateException("BUY cumulative fill did not advance monotonically");
        }
        if (!"buy".equals(side) && quantity.signum() <= 0) {
            throw new IllegalStateException("SELL cumulative fill did not advance monotonically");
        }
    }
    private static BigDecimal zeroIfNull(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
