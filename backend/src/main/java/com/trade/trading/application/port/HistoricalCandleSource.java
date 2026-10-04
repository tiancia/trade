package com.trade.trading.application.port;

import com.trade.client.okx.dto.CandleResp;

import java.time.Instant;
import java.util.List;

/** Historical market input for backtests; fetching and caching belong to adapters. */
public interface HistoricalCandleSource {
    List<CandleResp> historyCandles(String instId, String bar, Instant from, Instant to, int maxCandles);
}
