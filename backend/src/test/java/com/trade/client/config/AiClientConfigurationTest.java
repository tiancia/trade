package com.trade.client.config;

import com.trade.client.ai.AiClientProperties;
import com.trade.client.ai.AiTextClient;
import com.trade.client.ai.OpenAiCompatibleAiTextClient;
import com.trade.client.gemini.GeminiAiTextClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AiClientConfigurationTest {
    private final AiClientConfiguration configuration = new AiClientConfiguration();

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
