package com.trade.client.gemini;

import com.trade.dto.ai.GeminiGenerateReq;
import com.trade.dto.ai.GeminiGenerateResp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeminiApiMethodTest {

    @Test
    void routesTextAndJsonGenerationToClient() {
        FakeGeminiClient client = new FakeGeminiClient();
        GeminiApi api = new GeminiApi(client);

        assertEquals("text-result", api.generateText("analyze market"));
        assertEquals("generateText", client.method);
        assertEquals("analyze market", client.prompt);

        assertEquals("json-result", api.generateJson("return decision json"));
        assertEquals("generateJson", client.method);
        assertEquals("return decision json", client.prompt);
    }

    @Test
    void routesRawGenerateContentToClient() {
        FakeGeminiClient client = new FakeGeminiClient();
        GeminiApi api = new GeminiApi(client);
        GeminiGenerateReq req = new GeminiGenerateReq();

        GeminiGenerateResp resp = api.generateContent(req);

        assertEquals(client.response, resp);
        assertEquals("generateContent", client.method);
        assertEquals(req, client.request);
    }

    private static class FakeGeminiClient extends GeminiClient {
        private final GeminiGenerateResp response = new GeminiGenerateResp();
        private String method;
        private String prompt;
        private GeminiGenerateReq request;

        private FakeGeminiClient() {
            super("test-api-key");
        }

        @Override
        public String generateText(String prompt) {
            this.method = "generateText";
            this.prompt = prompt;
            return "text-result";
        }

        @Override
        public String generateJson(String prompt) {
            this.method = "generateJson";
            this.prompt = prompt;
            return "json-result";
        }

        @Override
        public GeminiGenerateResp generateContent(GeminiGenerateReq req) {
            this.method = "generateContent";
            this.request = req;
            return response;
        }
    }
}
