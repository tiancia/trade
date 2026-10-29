package com.trade.trading.infrastructure.config;

import com.trade.trading.domain.risk.RiskRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Wires the ordered risk rules without framework dependencies in the domain. */
@Configuration(proxyBeanMethods = false)
public class TradingRiskConfiguration {
    @Bean("tradingRiskRules")
    public List<RiskRule> tradingRiskRules() {
        // The first violation supplies the skip reason and primary-rule metric.
        return RiskRule.standardRules();
    }
}
