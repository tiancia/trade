package com.trade.trading.domain.risk;

import java.util.Optional;

public interface RiskRule {
    static java.util.List<RiskRule> standardRules() {
        return java.util.List.of(new LossCooldownRule(), new MaxDrawdownRule(), new DailyLossRule(),
                new OpenIntervalRule(), new ConsecutiveOpenActionsRule(), new SingleOpenExposureRule());
    }
    Optional<RiskViolation> evaluate(RiskContext context);
}
