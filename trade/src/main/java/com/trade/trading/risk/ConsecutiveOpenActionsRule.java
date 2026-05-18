package com.trade.trading.risk;

import com.trade.trading.config.TradingProperties;

import java.util.Optional;

public class ConsecutiveOpenActionsRule implements RiskRule {
    @Override
    public Optional<RiskViolation> evaluate(RiskContext context) {
        if (!context.isExecutableOpenAction() || context.getRiskState() == null) {
            return Optional.empty();
        }

        TradingProperties.RiskProperties riskProperties = context.getProperties().getRisk();
        int limit = riskProperties.getMaxConsecutiveOpenActions();
        if (limit <= 0 || context.getRiskState().getConsecutiveOpenActions() < limit) {
            return Optional.empty();
        }

        return Optional.of(RiskViolation.of(
                "RISK_CONSECUTIVE_OPEN_ACTIONS",
                "RISK_CONSECUTIVE_OPEN_ACTIONS: consecutive open actions reached "
                        + context.getRiskState().getConsecutiveOpenActions()
                        + ", maximum is "
                        + limit
        ));
    }
}
