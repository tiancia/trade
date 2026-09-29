package com.trade.polymarket.domain.model;

import com.trade.common.support.TradingMath;
import java.math.BigDecimal;

/** Immutable depth observation. Decimal text preserves price/size precision in existing audit JSON. */
public record MarketDepthLevel(String price, String size) {
    public BigDecimal quoteNotional() {
        return TradingMath.decimal(price).multiply(TradingMath.decimal(size));
    }
}
