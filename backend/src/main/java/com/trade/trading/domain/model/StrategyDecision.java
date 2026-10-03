package com.trade.trading.domain.model;

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

    public static StrategyDecision buy(String strategyId, BigDecimal quoteAmount, String reason) {
        return create(strategyId, TradingAction.BUY, reason)
                .setBuyQuoteAmount(quoteAmount);
    }

    public static StrategyDecision sell(String strategyId, BigDecimal baseAmount, String reason) {
        return create(strategyId, TradingAction.SELL, reason)
                .setSellBaseAmount(baseAmount);
    }

    public static StrategyDecision openLong(String strategyId, BigDecimal orderSize, String reason) {
        return create(strategyId, TradingAction.OPEN_LONG, reason)
                .setOrderSize(orderSize);
    }

    public static StrategyDecision closeLong(String strategyId, BigDecimal orderSize, String reason) {
        return create(strategyId, TradingAction.CLOSE_LONG, reason)
                .setOrderSize(orderSize);
    }

    public static StrategyDecision hold(String strategyId, String reason) {
        return create(strategyId, TradingAction.HOLD, reason);
    }

    private static StrategyDecision create(String strategyId, TradingAction action, String reason) {
        return new StrategyDecision()
                .setStrategyId(strategyId)
                .setAction(action)
                .setReason(reason);
    }

    public boolean isHold() {
        return action == null || action == TradingAction.HOLD;
    }
}
