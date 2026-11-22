package com.trade.polymarket.domain.model;

import com.trade.common.support.TradingMath;
import java.math.BigDecimal;

/** Immutable depth observation. Decimal text preserves price/size precision in existing audit JSON. */
public record MarketDepthLevel(String price, String size) {
    public BigDecimal priceValue() {
        return TradingMath.decimal(price);
    }

    public boolean executable() {
        BigDecimal value = priceValue();
        return value.signum() > 0 && value.compareTo(BigDecimal.ONE) <= 0 && TradingMath.decimal(size).signum() > 0;
    }

    public BigDecimal quoteNotional() {
        return priceValue().multiply(TradingMath.decimal(size));
    }
}
