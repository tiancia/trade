package com.trade.trading.strategy;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingState;
import com.trade.trading.model.TradingTrigger;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;

@Data
@Accessors(chain = true)
public class StrategyEvaluationContext {
    private String strategyId;
    private String bar;
    private TradingTrigger trigger;
    private TradingDecisionContext marketContext;
    private TradingProperties properties;
    private Instant evaluatedAt = Instant.now();

    public TickerResp ticker() {
        return marketContext == null ? null : marketContext.getTicker();
    }

    public TradingState tradingState() {
        return marketContext == null ? null : marketContext.getTradingState();
    }

    public List<CandleResp> candlesNewestFirst() {
        if (marketContext == null) {
            return List.of();
        }
        String normalizedBar = bar == null ? "" : bar.trim().toLowerCase();
        if ("5m".equals(normalizedBar)) {
            return marketContext.getFiveMinuteCandles() == null ? List.of() : marketContext.getFiveMinuteCandles();
        }
        return marketContext.getOneMinuteCandles() == null ? List.of() : marketContext.getOneMinuteCandles();
    }
}
