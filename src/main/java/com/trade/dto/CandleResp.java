package com.trade.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;

import java.util.List;

@Data
public class CandleResp {
    private String ts;
    private String open;
    private String high;
    private String low;
    private String close;
    private String vol;
    private String volCcy;
    private String volCcyQuote;
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
