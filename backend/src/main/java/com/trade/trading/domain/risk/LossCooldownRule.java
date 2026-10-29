package com.trade.trading.domain.risk;

import static com.trade.trading.domain.model.TradingStateTimestamps.parseOrNull;

import java.time.Instant;
import java.util.Optional;

public class LossCooldownRule implements RiskRule {
    @Override
    public Optional<RiskViolation> evaluate(RiskContext context) {
        if (!context.isExecutableOpenAction() || context.getRiskState() == null) {
            return Optional.empty();
        }

        Instant cooldownUntil = parseOrNull(context.getRiskState().getLossCooldownUntil());
        Instant now = context.getNow();
        if (cooldownUntil == null || now == null || !now.isBefore(cooldownUntil)) {
            return Optional.empty();
        }

        return Optional.of(RiskViolation.of(
                "RISK_COOLDOWN",
                "RISK_COOLDOWN: consecutive losses reached "
                        + context.getRiskState().getConsecutiveLosses()
                        + ", cooldown until "
                        + cooldownUntil
        ));
    }
}
