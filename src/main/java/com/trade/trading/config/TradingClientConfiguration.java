package com.trade.trading.config;

import com.trade.client.ai.AiClientProperties;
import com.trade.client.gemini.GeminiApi;
import com.trade.client.gemini.GeminiClient;
import com.trade.client.gemini.GeminiClientProperties;
import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxClient;
import com.trade.client.okx.OkxClientProperties;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.OkxWebSocketClient;
import com.trade.trading.ai.AiTextClient;
import com.trade.trading.ai.GeminiAiTextClient;
import com.trade.trading.ai.OpenAiCompatibleAiTextClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@EnableConfigurationProperties({
        AiTradingProperties.class,
        AiClientProperties.class,
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

    @Bean
    public AiTextClient aiTextClient(AiClientProperties properties) {
        return switch (properties.getProvider()) {
            case GEMINI -> new GeminiAiTextClient(() -> new GeminiApi(new GeminiClient(properties)));
            case OPENAI_COMPATIBLE, DEEPSEEK, KIMI -> new OpenAiCompatibleAiTextClient(properties);
        };
    }
}
