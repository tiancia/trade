package com.trade.trading.domain.risk;

import static com.trade.trading.domain.model.TradingStateTimestamps.parseOrNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class OpenIntervalRule implements RiskRule {
    @Override
    public Optional<RiskViolation> evaluate(RiskContext context) {
        if (!context.isExecutableOpenAction() || context.getRiskState() == null) {
            return Optional.empty();
        }

        RiskPolicy riskProperties = context.getPolicy();
        long minOpenIntervalMs = riskProperties.getMinOpenIntervalMs();
        Instant lastTradeTime = parseOrNull(context.getRiskState().getLastTradeTime());
        Instant now = context.getNow();
        if (minOpenIntervalMs <= 0 || lastTradeTime == null || now == null) {
            return Optional.empty();
        }

        long elapsedMs = Math.max(0, Duration.between(lastTradeTime, now).toMillis());
        if (elapsedMs >= minOpenIntervalMs) {
            return Optional.empty();
        }

        return Optional.of(RiskViolation.of(
                "RISK_MIN_OPEN_INTERVAL",
                "RISK_MIN_OPEN_INTERVAL: only "
                        + elapsedMs
                        + "ms since last trade, minimum is "
                        + minOpenIntervalMs
                        + "ms"
        ));
    }
}
