package com.trade.trading.application.port;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingTrigger;
import java.util.List;

/** On-demand context collection; provider calls and event publication belong to adapters. */
public interface TradingMarketSource {
    TradingDecisionContext collect(TradingTrigger trigger);
    List<CandleResp> getOneMinuteCandles();
    TickerResp getTicker();
}
