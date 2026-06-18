package com.trade.trading.risk;

import com.trade.trading.config.TradingProperties;
import com.trade.common.support.TradingMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public class DailyLossRule implements RiskRule {
    @Override
    public Optional<RiskViolation> evaluate(RiskContext context) {
        if (!context.isExecutableOpenAction() || context.getRiskState() == null) {
            return Optional.empty();
        }

        TradingProperties.RiskProperties riskProperties = context.getProperties().getRisk();
        BigDecimal limit = riskProperties.getMaxDailyLossRatio();
        BigDecimal dayStartEquity = context.getRiskState().getDayStartEquity();
        BigDecimal currentEquity = context.getCurrentEquity();
        if (limit == null || limit.signum() <= 0
                || dayStartEquity == null || dayStartEquity.signum() <= 0
                || currentEquity == null || currentEquity.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal dailyLoss = dayStartEquity.subtract(currentEquity)
                .divide(dayStartEquity, 10, RoundingMode.HALF_UP);
        if (dailyLoss.compareTo(limit) < 0) {
            return Optional.empty();
        }

        return Optional.of(RiskViolation.of(
                "RISK_DAILY_LOSS",
                "RISK_DAILY_LOSS: daily equity loss "
                        + percent(dailyLoss)
                        + " reached limit "
                        + percent(limit)
        ));
    }

    private static String percent(BigDecimal ratio) {
        return TradingMath.plain(ratio.multiply(new BigDecimal("100"))) + "%";
    }
}
