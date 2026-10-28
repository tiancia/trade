package com.trade.trading.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TradingAiProperties.class)
public class TradingAiConfiguration {
    @org.springframework.context.annotation.Bean
    public org.springframework.beans.factory.InitializingBean tradingAiConfigurationGuard(TradingAiProperties ai,
                                                                                         TradingProperties trading) {
        return () -> {
            ai.validate();
            if (ai.isEnabled() && ai.getCandleCount() >= trading.getFifteenMinuteCandleLimit()) {
                throw new IllegalArgumentException("Trading AI candle-count must be below fifteen-minute-candle-limit");
            }
        };
    }
}
