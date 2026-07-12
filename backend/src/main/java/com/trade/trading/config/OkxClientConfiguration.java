package com.trade.trading.config;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxClient;
import com.trade.client.okx.OkxClientProperties;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.OkxWebSocketClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OKX transport clients used by the trading domain.
 *
 * <p>Shared AI-provider beans are configured separately under
 * {@code com.trade.client.config}; keeping them out of this class prevents
 * story and Polymarket workflows from depending on trading configuration.</p>
 */
@Configuration
@EnableConfigurationProperties({
        TradingProperties.class,
        OkxClientProperties.class
})
public class OkxClientConfiguration {

    @Bean
    public OkxRestClient okxRestClient(OkxClientProperties properties) {
        return new OkxClient(properties);
    }

    @Bean
    public OkxWebSocketClient okxWebSocketClient(OkxClientProperties properties) {
        return new OkxWebSocketClient(properties);
    }

    @Bean
    public OkxApi okxApi(OkxRestClient okxRestClient, OkxWebSocketClient okxWebSocketClient) {
        return new OkxApi(okxRestClient, okxWebSocketClient);
    }
}
