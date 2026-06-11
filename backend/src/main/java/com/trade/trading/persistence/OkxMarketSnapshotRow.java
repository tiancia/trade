package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** Database row for one collected OKX ticker and/or order-book snapshot. */
@Data
@Accessors(chain = true)
public class OkxMarketSnapshotRow {
    /** Auto-increment database identifier. */
    private Long id;
    /** OKX instrument identifier, for example BTC-USDT. */
    private String instId;
    /** Collection path such as REST_DECISION or WEBSOCKET_TICKER. */
    private String source;
    /** Exchange timestamp from the ticker, in epoch milliseconds. */
    private Long marketTs;
    /** Last traded price. */
    private BigDecimal lastPrice;
    /** Size of the last trade. */
    private BigDecimal lastSize;
    /** Best bid price. */
    private BigDecimal bidPrice;
    /** Size available at the best bid. */
    private BigDecimal bidSize;
    /** Best ask price. */
    private BigDecimal askPrice;
    /** Size available at the best ask. */
    private BigDecimal askSize;
    /** Price at the start of the rolling 24-hour period. */
    private BigDecimal open24h;
    /** Highest price in the rolling 24-hour period. */
    private BigDecimal high24h;
    /** Lowest price in the rolling 24-hour period. */
    private BigDecimal low24h;
    /** Rolling 24-hour volume denominated in currency. */
    private BigDecimal volCcy24h;
    /** Rolling 24-hour volume denominated in contracts or base units. */
    private BigDecimal vol24h;
    /** Exchange timestamp from the order book, in epoch milliseconds. */
    private Long orderBookTs;
    /** OKX order-book sequence identifier. */
    private Long sequenceId;
    /** Original ticker payload serialized as JSON. */
    private String tickerJson;
    /** Original order-book payload serialized as JSON. */
    private String orderBookJson;
}
