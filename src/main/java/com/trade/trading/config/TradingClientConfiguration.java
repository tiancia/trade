package com.trade.trading.config;

import com.trade.client.gemini.GeminiApi;
import com.trade.client.gemini.GeminiClient;
import com.trade.client.gemini.GeminiClientProperties;
import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxClient;
import com.trade.client.okx.OkxClientProperties;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.OkxWebSocketClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@EnableConfigurationProperties({
        AiTradingProperties.class,
        OkxClientProperties.class,
        GeminiClientProperties.class
})
public class TradingClientConfiguration {

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

    @Bean
    @Lazy
    public GeminiApi geminiApi(GeminiClientProperties properties) {
        return new GeminiApi(new GeminiClient(properties));
    }
}
