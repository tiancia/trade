package com.trade.trading.domain.order;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Simulated spot fill accounting without persistence or exchange access. */
public final class PaperFillPolicy {
    private PaperFillPolicy() {}
    public record BuyFill(BigDecimal grossBase, BigDecimal netBase, BigDecimal averageCost, BigDecimal fee) {}
    public static BuyFill buy(BigDecimal quoteAmount, BigDecimal price, BigDecimal feeRate) {
        BigDecimal grossBase = quoteAmount.divide(price, 18, RoundingMode.DOWN);
        BigDecimal feeBase = grossBase.multiply(feeRate);
        BigDecimal netBase = grossBase.subtract(feeBase);
        return new BuyFill(grossBase, netBase, quoteAmount.divide(netBase, 18, RoundingMode.HALF_UP), feeBase.negate());
    }
    public static BigDecimal sellFee(BigDecimal baseAmount, BigDecimal price, BigDecimal feeRate) {
        return baseAmount.multiply(price).multiply(feeRate).negate();
    }
}
