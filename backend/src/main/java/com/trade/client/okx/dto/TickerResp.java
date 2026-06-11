package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Public OKX ticker payload for one instrument. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TickerResp {
    /** Instrument type returned by OKX. */
    private String instType;
    /** Instrument identifier, for example BTC-USDT. */
    private String instId;
    /** Last traded price. */
    private String last;
    /** Size of the last trade. */
    private String lastSz;
    /** Best ask price. */
    private String askPx;
    /** Size available at the best ask. */
    private String askSz;
    /** Best bid price. */
    private String bidPx;
    /** Size available at the best bid. */
    private String bidSz;
    /** Price at the start of the rolling 24-hour period. */
    private String open24h;
    /** Highest price in the rolling 24-hour period. */
    private String high24h;
    /** Lowest price in the rolling 24-hour period. */
    private String low24h;
    /** Rolling 24-hour volume denominated in currency. */
    private String volCcy24h;
    /** Rolling 24-hour volume denominated in contracts or base units. */
    private String vol24h;
    /** Exchange timestamp in epoch milliseconds. */
    private String ts;
    /** Price at the start of the UTC day. */
    private String sodUtc0;
    /** Price at the start of the UTC+8 day. */
    private String sodUtc8;
}
