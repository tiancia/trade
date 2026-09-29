package com.trade.trading.domain.risk;

import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingRiskState;
import lombok.Data;
import lombok.experimental.Accessors;
import java.math.BigDecimal;
import java.time.Instant;

/** Facts for one risk evaluation; no provider DTO or infrastructure object. */
@Data
@Accessors(chain = true)
public class RiskContext {
    private StrategyDecision decision;
    private RiskPolicy policy;
    private boolean spotInstrument;
    private boolean derivativeInstrument;
    private boolean shortEnabled;
    private TradingRiskState riskState;
    private Instant now;
    private BigDecimal currentEquity = BigDecimal.ZERO;
    private BigDecimal lastPrice = BigDecimal.ZERO;

    public TradingAction action() {
        return decision == null ? null : decision.getAction();
    }

    public boolean isExecutableOpenAction() {
        TradingAction action = action();
        if (action == null || !action.isOpenAction()) {
            return false;
        }
        return switch (action) {
            case BUY -> spotInstrument;
            case OPEN_LONG -> derivativeInstrument;
            case OPEN_SHORT -> derivativeInstrument && shortEnabled;
            default -> false;
        };
    }

    public BigDecimal requestedOpenExposure() {
        if (decision == null || action() == null) {
            return BigDecimal.ZERO;
        }
        return switch (action()) {
            case BUY -> zeroIfNull(decision.getBuyQuoteAmount());
            case OPEN_LONG, OPEN_SHORT -> zeroIfNull(decision.getOrderSize()).multiply(zeroIfNull(lastPrice));
            default -> BigDecimal.ZERO;
        };
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
