package com.trade.polymarket.config;

import com.trade.client.polymarket.PolymarketApi;
import com.trade.client.polymarket.PolymarketClientProperties;
import com.trade.client.polymarket.PolymarketHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        AiPolymarketProperties.class,
        PolymarketClientProperties.class
})
public class PolymarketConfiguration {
    @Bean
    public PolymarketApi polymarketApi(PolymarketClientProperties properties) {
        return new PolymarketApi(new PolymarketHttpClient(properties));
    }
}
