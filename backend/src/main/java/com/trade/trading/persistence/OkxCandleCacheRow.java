package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** Database row keyed by OKX instrument, candle interval, and exchange timestamp. */
@Data
@Accessors(chain = true)
public class OkxCandleCacheRow {
    /** OKX instrument identifier, for example BTC-USDT. */
    private String instId;
    /** Candlestick interval, for example 1m or 5m. */
    private String bar;
    /** Candle opening timestamp in epoch milliseconds. */
    private Long ts;
    /** Opening price. */
    private BigDecimal open;
    /** Highest price during the candle. */
    private BigDecimal high;
    /** Lowest price during the candle. */
    private BigDecimal low;
    /** Latest or final closing price. */
    private BigDecimal close;
    /** Trading volume in contracts or base units. */
    private BigDecimal vol;
    /** Trading volume denominated in currency. */
    private BigDecimal volCcy;
    /** Trading volume denominated in quote currency. */
    private BigDecimal volCcyQuote;
    /** OKX completion flag: 1 means closed, 0 means still forming. */
    private String confirm;
}
