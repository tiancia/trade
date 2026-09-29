package com.trade.trading.application.market;

import com.trade.trading.domain.market.MarketSignalPolicy;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.common.support.TradingMath;
import com.trade.trading.domain.model.MarketSignal;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts raw market snapshots into lightweight triggers for the scheduler.
 * Detection is intentionally stateless except for local position data supplied
 * by {@link TradingState}.
 */
@Component
public class TradingEventDetector {
    private final TradingProperties properties;

    public TradingEventDetector(TradingProperties properties) {
        this.properties = properties;
    }

    public List<MarketSignal> detect(TickerResp ticker, List<CandleResp> oneMinuteCandles, TradingState state) {
        var candles = oneMinuteCandles == null ? List.<MarketSignalPolicy.Candle>of()
                : oneMinuteCandles.stream().map(candle -> new MarketSignalPolicy.Candle(
                        TradingMath.decimal(candle.getClose()), TradingMath.decimal(candle.getVolCcyQuote()),
                        "1".equals(candle.getConfirm()))).toList();
        return new MarketSignalPolicy(new MarketSignalPolicy.Thresholds(
                properties.getPriceMoveTriggerPercent(), properties.getVolumeSpikeMultiplier(),
                properties.getFloatingLossTriggerPercent()))
                .detect(TradingMath.decimal(ticker == null ? null : ticker.getLast()), candles, state);
    }

}
