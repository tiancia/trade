package com.trade.trading.infrastructure.config;

import com.trade.trading.domain.risk.ConsecutiveOpenActionsRule;
import com.trade.trading.domain.risk.DailyLossRule;
import com.trade.trading.domain.risk.LossCooldownRule;
import com.trade.trading.domain.risk.MaxDrawdownRule;
import com.trade.trading.domain.risk.OpenIntervalRule;
import com.trade.trading.domain.risk.RiskRule;
import com.trade.trading.domain.risk.SingleOpenExposureRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Wires the ordered risk rules without framework dependencies in the domain. */
@Configuration(proxyBeanMethods = false)
public class TradingRiskConfiguration {
    @Bean("tradingRiskRules")
    public List<RiskRule> tradingRiskRules() {
        // The first violation supplies the skip reason and primary-rule metric.
        return List.of(
                new LossCooldownRule(),
                new MaxDrawdownRule(),
                new DailyLossRule(),
                new OpenIntervalRule(),
                new ConsecutiveOpenActionsRule(),
                new SingleOpenExposureRule()
        );
    }
}
