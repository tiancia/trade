package com.trade.client.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientPropertiesTest {
    @Test
    void directApiKeyTakesPrecedenceOverEnvironmentName() {
        AiClientProperties properties = new AiClientProperties();
        properties.setApiKey(" direct-key ");
        properties.setApiKeyEnvName("MISSING_TEST_AI_KEY");

        assertEquals("direct-key", properties.requiredApiKey());
    }

    @Test
    void missingApiKeyMentionsConfiguredEnvironmentName() {
        AiClientProperties properties = new AiClientProperties();
        properties.setApiKeyEnvName("MISSING_TEST_AI_KEY");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                properties::requiredApiKey
        );

        assertTrue(error.getMessage().contains("MISSING_TEST_AI_KEY"));
    }

    @Test
    void normalizesBaseUrlAndChatCompletionsPath() {
        AiClientProperties properties = new AiClientProperties();
        properties.setBaseUrl("https://api.example.com///");
        properties.setChatCompletionsPath("chat/completions");

        assertEquals("https://api.example.com", properties.normalizedBaseUrl());
        assertEquals("/chat/completions", properties.normalizedChatCompletionsPath());
    }

    @Test
    void usesDeepSeekDefaults() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.DEEPSEEK);

        assertEquals("https://api.deepseek.com", properties.normalizedBaseUrl());
        assertEquals("deepseek-v4-flash", properties.requiredModel());
        assertEquals("/chat/completions", properties.normalizedChatCompletionsPath());
    }

    @Test
    void usesKimiDefaults() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.KIMI);

        assertEquals("https://api.moonshot.ai/v1", properties.normalizedBaseUrl());
        assertEquals("kimi-k2.6", properties.requiredModel());
        assertEquals("/chat/completions", properties.normalizedChatCompletionsPath());
    }

    @Test
    void openAiCompatibleRequiresExplicitBaseUrlAndModel() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.OPENAI_COMPATIBLE);

        assertThrows(IllegalArgumentException.class, properties::normalizedBaseUrl);
        assertThrows(IllegalArgumentException.class, properties::requiredModel);
    }
}
