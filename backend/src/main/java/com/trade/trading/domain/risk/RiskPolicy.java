package com.trade.trading.domain.risk;

import java.math.BigDecimal;
import lombok.Value;

/** Resolved business limits; independent of configuration binding and providers. */
@Value
public class RiskPolicy {
    boolean enabled;
    int maxConsecutiveLosses;
    long lossCooldownMs;
    BigDecimal maxDrawdownRatio;
    BigDecimal maxDailyLossRatio;
    String dailyZone;
    long minOpenIntervalMs;
    int maxConsecutiveOpenActions;
    BigDecimal maxSingleOpenEquityRatio;
    BigDecimal equityNoiseRatio;
}
