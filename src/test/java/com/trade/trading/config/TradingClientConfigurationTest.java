package com.trade.trading.config;

import com.trade.client.ai.AiClientProperties;
import com.trade.client.ai.AiTextClient;
import com.trade.client.ai.GeminiAiTextClient;
import com.trade.client.ai.OpenAiCompatibleAiTextClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TradingClientConfigurationTest {
    private final TradingClientConfiguration configuration = new TradingClientConfiguration();

    @Test
    void selectsGeminiAiTextClient() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.GEMINI);

        AiTextClient client = configuration.aiTextClient(properties);

        assertInstanceOf(GeminiAiTextClient.class, client);
    }

    @Test
    void selectsOpenAiCompatibleAiTextClient() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.OPENAI_COMPATIBLE);

        AiTextClient client = configuration.aiTextClient(properties);

        assertInstanceOf(OpenAiCompatibleAiTextClient.class, client);
    }

    @Test
    void selectsDeepSeekAiTextClient() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.DEEPSEEK);

        AiTextClient client = configuration.aiTextClient(properties);

        assertInstanceOf(OpenAiCompatibleAiTextClient.class, client);
    }

    @Test
    void selectsKimiAiTextClient() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.KIMI);

        AiTextClient client = configuration.aiTextClient(properties);

        assertInstanceOf(OpenAiCompatibleAiTextClient.class, client);
    }
}
