package com.trade.client.gemini;

import com.trade.dto.ai.GeminiGenerateReq;
import com.trade.dto.ai.GeminiGenerateResp;

public class GeminiApi {

    private final GeminiClient geminiClient;

    public GeminiApi() {
        this(new GeminiClient());
    }

    public GeminiApi(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public String generateText(String prompt) {
        return geminiClient.generateText(prompt);
    }

    public String generateJson(String prompt) {
        return geminiClient.generateJson(prompt);
    }

    public GeminiGenerateResp generateContent(GeminiGenerateReq req) {
        return geminiClient.generateContent(req);
    }
}
