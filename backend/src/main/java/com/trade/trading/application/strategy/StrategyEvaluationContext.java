package com.trade.trading.application.strategy;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.model.TradingTrigger;
import com.trade.trading.infrastructure.config.TradingProperties;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.math.BigDecimal;
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
    /** Historical execution assumptions; null uses deployment estimates. */
    private BigDecimal evaluationFeeRate;
    private BigDecimal evaluationSlippageRate;

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
        List<CandleResp> candles = switch (normalizedBar) {
            case "1m" -> marketContext.getOneMinuteCandles();
            case "5m" -> marketContext.getFiveMinuteCandles();
            case "15m" -> marketContext.getFifteenMinuteCandles();
            default -> List.of();
        };
        return candles == null ? List.of() : candles;
    }
}
