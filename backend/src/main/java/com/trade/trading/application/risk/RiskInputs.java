package com.trade.trading.application.risk;

import com.trade.trading.domain.risk.RiskPolicy;
import com.trade.trading.infrastructure.config.TradingProperties;

/** Translates bound risk configuration into domain policy parameters. */
public final class RiskInputs {
    private RiskInputs() {}

    public static RiskPolicy policy(TradingProperties.RiskProperties source) {
        return new RiskPolicy(source.isEnabled(), source.getMaxConsecutiveLosses(),
                source.getLossCooldownMs(), source.getMaxDrawdownRatio(), source.getMaxDailyLossRatio(),
                source.getDailyZone(), source.getMinOpenIntervalMs(), source.getMaxConsecutiveOpenActions(),
                source.getMaxSingleOpenEquityRatio(), source.getEquityNoiseRatio());
    }

}
