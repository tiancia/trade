package com.trade.client.gemini;

import com.trade.client.ai.AiTextClient;

import java.util.function.Supplier;

/**
 * Lazy adapter from the shared {@link AiTextClient} interface to GeminiApi.
 */
public class GeminiAiTextClient implements AiTextClient {
    private final Supplier<GeminiApi> geminiApiSupplier;
    // Lazily initialized so tests/configuration can supply a factory without
    // creating the HTTP client until the first generation request.
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
