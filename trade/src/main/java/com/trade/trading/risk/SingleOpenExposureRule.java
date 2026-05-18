package com.trade.trading.risk;

import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingAction;
import com.trade.trading.support.TradingMath;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public class SingleOpenExposureRule implements RiskRule {
    @Override
    public Optional<RiskViolation> evaluate(RiskContext context) {
        if (!context.isExecutableOpenAction() || context.action() == TradingAction.BUY) {
            return Optional.empty();
        }

        TradingProperties.RiskProperties riskProperties = context.getProperties().getRisk();
        BigDecimal ratio = riskProperties.getMaxSingleOpenEquityRatio();
        BigDecimal currentEquity = context.getCurrentEquity();
        if (ratio == null || ratio.signum() <= 0
                || currentEquity == null || currentEquity.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal maxExposure = currentEquity.multiply(ratio);
        BigDecimal requestedExposure = context.requestedOpenExposure();
        if (requestedExposure.signum() <= 0 || requestedExposure.compareTo(maxExposure) <= 0) {
            return Optional.empty();
        }

        BigDecimal requestedRatio = requestedExposure.divide(currentEquity, 10, RoundingMode.HALF_UP);
        return Optional.of(RiskViolation.of(
                "RISK_SINGLE_OPEN_EXPOSURE",
                "RISK_SINGLE_OPEN_EXPOSURE: requested open exposure "
                        + TradingMath.plain(requestedExposure)
                        + " is "
                        + percent(requestedRatio)
                        + " of equity, maximum is "
                        + percent(ratio)
        ));
    }

    private static String percent(BigDecimal ratio) {
        return TradingMath.plain(ratio.multiply(new BigDecimal("100"))) + "%";
    }
}
