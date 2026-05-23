package com.trade.trading.risk;

import com.trade.trading.config.TradingProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class OpenIntervalRule implements RiskRule {
    @Override
    public Optional<RiskViolation> evaluate(RiskContext context) {
        if (!context.isExecutableOpenAction() || context.getRiskState() == null) {
            return Optional.empty();
        }

        TradingProperties.RiskProperties riskProperties = context.getProperties().getRisk();
        long minOpenIntervalMs = riskProperties.getMinOpenIntervalMs();
        Instant lastTradeTime = parseInstant(context.getRiskState().getLastTradeTime());
        Instant now = context.getNow();
        if (minOpenIntervalMs <= 0 || lastTradeTime == null || now == null || !now.isAfter(lastTradeTime)) {
            return Optional.empty();
        }

        long elapsedMs = Duration.between(lastTradeTime, now).toMillis();
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

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
