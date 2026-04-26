package com.trade.trading.ai;

import com.trade.client.gemini.GeminiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class GeminiAiTextClient implements AiTextClient {
    private final ObjectProvider<GeminiApi> geminiApiProvider;

    public GeminiAiTextClient(ObjectProvider<GeminiApi> geminiApiProvider) {
        this.geminiApiProvider = geminiApiProvider;
    }

    @Override
    public String generateJson(String prompt) {
        return geminiApiProvider.getObject().generateJson(prompt);
    }
}
