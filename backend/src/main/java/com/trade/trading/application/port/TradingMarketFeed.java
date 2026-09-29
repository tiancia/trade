package com.trade.trading.application.port;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import java.util.List;
import java.util.Optional;

/** Live market feed lifecycle and latest observations. */
public interface TradingMarketFeed {
    void start();
    void stop();
    Optional<TickerResp> latestTicker();
    List<CandleResp> recentOneMinuteCandles(int limit);
}
