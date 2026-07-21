package com.trade.trading.market;

import com.trade.client.okx.dto.CandleResp;

import java.time.Instant;
import java.util.List;

/** Chronological candle snapshot returned to operator clients. */
public record CandleSeries(
        String instrumentId,
        String bar,
        String source,
        Instant asOf,
        List<CandleResp> candles
) {
    public CandleSeries {
        candles = candles == null ? List.of() : List.copyOf(candles);
    }
}
