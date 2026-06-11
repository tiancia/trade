package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;

import java.util.List;

/** Public OKX candlestick payload. Numeric values remain strings to preserve exchange precision. */
@Data
public class CandleResp {
    /** Candle opening timestamp in epoch milliseconds. */
    private String ts;
    /** Opening price. */
    private String open;
    /** Highest price during the candle. */
    private String high;
    /** Lowest price during the candle. */
    private String low;
    /** Latest or final closing price. */
    private String close;
    /** Trading volume in contracts or base units. */
    private String vol;
    /** Trading volume denominated in currency. */
    private String volCcy;
    /** Trading volume denominated in quote currency. */
    private String volCcyQuote;
    /** Completion flag: 1 means closed and 0 means still forming. */
    private String confirm;

    public CandleResp() {
    }

    /**
     * Maps OKX candle arrays: [ts, open, high, low, close, vol, volCcy, volCcyQuote, confirm].
     */
    @JsonCreator
    public CandleResp(List<String> values) {
        this.ts = get(values, 0);
        this.open = get(values, 1);
        this.high = get(values, 2);
        this.low = get(values, 3);
        this.close = get(values, 4);
        this.vol = get(values, 5);
        this.volCcy = get(values, 6);
        this.volCcyQuote = get(values, 7);
        this.confirm = get(values, 8);
    }

    private static String get(List<String> values, int index) {
        return values != null && values.size() > index ? values.get(index) : null;
    }
}
