package com.trade.trading.domain.model;

import java.math.BigDecimal;
import java.util.Map;

/** Advisory output has no order, deployment or executable-code capability. */
public record TradingAiProposal(Action action, String strategyId, Map<String, Object> parameters,
                                BigDecimal confidence, String marketRegime, String reason,
                                String logicSuggestion) {
    public enum Action { HOLD, SWITCH, TUNE }
}
