package com.trade.client.ai;

import com.trade.client.gemini.GeminiApi;

import java.util.function.Supplier;

public class GeminiAiTextClient implements AiTextClient {
    private final Supplier<GeminiApi> geminiApiSupplier;
    private volatile GeminiApi geminiApi;

    public GeminiAiTextClient(GeminiApi geminiApi) {
        this(() -> geminiApi);
    }

    public GeminiAiTextClient(Supplier<GeminiApi> geminiApiSupplier) {
        this.geminiApiSupplier = geminiApiSupplier;
    }

    @Override
    public String generateJson(String prompt) {
        return geminiApi().generateJson(prompt);
    }

    private GeminiApi geminiApi() {
        GeminiApi current = geminiApi;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (geminiApi == null) {
                geminiApi = geminiApiSupplier.get();
            }
            return geminiApi;
        }
    }
}
