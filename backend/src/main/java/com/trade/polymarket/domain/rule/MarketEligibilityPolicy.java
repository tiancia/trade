package com.trade.polymarket.domain.rule;

import lombok.Value;
import java.math.BigDecimal;

/** Resolved limits for market horizon, turnover, spread, and available liquidity. */
@Value
public class MarketEligibilityPolicy {
    boolean requireMarketEndDate;
    long minTimeToResolutionMinutes;
    long maxTimeToResolutionHours;
    BigDecimal minMarketVolume24hr;
    BigDecimal minMarketLiquidity;
    BigDecimal maxOutcomeSpread;
    BigDecimal minOutcomeAskLiquidityUsdc;
}
