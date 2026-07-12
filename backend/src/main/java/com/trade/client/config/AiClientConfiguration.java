package com.trade.client.config;

import com.trade.client.ai.AiClientProperties;
import com.trade.client.ai.AiTextClient;
import com.trade.client.ai.OpenAiCompatibleAiTextClient;
import com.trade.client.gemini.GeminiAiTextClient;
import com.trade.client.gemini.GeminiApi;
import com.trade.client.gemini.GeminiClient;
import com.trade.client.gemini.GeminiClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Selects and wires the shared AI text provider for AI-powered workflows.
 *
 * <p>The configuration sits above provider packages because it is the only
 * place that should know which concrete provider implements
 * {@link AiTextClient}.</p>
 */
@Configuration
@EnableConfigurationProperties({
        AiClientProperties.class,
        GeminiClientProperties.class
})
public class AiClientConfiguration {

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
