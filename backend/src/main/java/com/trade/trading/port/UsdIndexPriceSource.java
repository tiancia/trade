package com.trade.trading.port;

import java.math.BigDecimal;

/** Current USD index value of one unit of the requested currency. */
@FunctionalInterface
public interface UsdIndexPriceSource {
    BigDecimal usdPrice(String currency);
}
