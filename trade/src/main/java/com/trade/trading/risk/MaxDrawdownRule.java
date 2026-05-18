package com.trade.trading.risk;

import com.trade.trading.config.TradingProperties;
import com.trade.trading.support.TradingMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public class MaxDrawdownRule implements RiskRule {
    @Override
    public Optional<RiskViolation> evaluate(RiskContext context) {
        if (!context.isExecutableOpenAction() || context.getRiskState() == null) {
            return Optional.empty();
        }

        TradingProperties.RiskProperties riskProperties = context.getProperties().getRisk();
        BigDecimal limit = riskProperties.getMaxDrawdownRatio();
        BigDecimal highWatermark = context.getRiskState().getEquityHighWatermark();
        BigDecimal currentEquity = context.getCurrentEquity();
        if (limit == null || limit.signum() <= 0
                || highWatermark == null || highWatermark.signum() <= 0
                || currentEquity == null || currentEquity.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal drawdown = highWatermark.subtract(currentEquity)
                .divide(highWatermark, 10, RoundingMode.HALF_UP);
        if (drawdown.compareTo(limit) < 0) {
            return Optional.empty();
        }

        return Optional.of(RiskViolation.of(
                "RISK_MAX_DRAWDOWN",
                "RISK_MAX_DRAWDOWN: equity drawdown "
                        + percent(drawdown)
                        + " reached limit "
                        + percent(limit)
        ));
    }

    private static String percent(BigDecimal ratio) {
        return TradingMath.plain(ratio.multiply(new BigDecimal("100"))) + "%";
    }
}
