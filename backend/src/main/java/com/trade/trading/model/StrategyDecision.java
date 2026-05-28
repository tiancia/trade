package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class StrategyDecision {
    private String strategyId;
    private TradingAction action = TradingAction.HOLD;
    private String reason;
    private BigDecimal buyQuoteAmount;
    private BigDecimal sellBaseAmount;
    private BigDecimal orderSize;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public static StrategyDecision hold(String strategyId, String reason) {
        return new StrategyDecision()
                .setStrategyId(strategyId)
                .setAction(TradingAction.HOLD)
                .setReason(reason);
    }

    public boolean isHold() {
        return action == null || action == TradingAction.HOLD;
    }
}
