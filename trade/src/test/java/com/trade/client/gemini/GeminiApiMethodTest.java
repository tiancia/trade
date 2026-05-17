package com.trade.client.gemini;

import com.trade.client.gemini.dto.GeminiGenerateReq;
import com.trade.client.gemini.dto.GeminiGenerateResp;
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

    @Test
    void parsesGemini3ResponseWithExtraMetadata() {
        RawGeminiClient client = new RawGeminiClient("""
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "{\\"action\\":\\"HOLD\\",\\"reason\\":\\"smoke test\\"}",
                            "thoughtSignature": "signature",
                            "unexpectedPartField": "ignored"
                          }
                        ],
                        "role": "model"
                      },
                      "finishReason": "STOP",
                      "index": 0,
                      "unexpectedCandidateField": "ignored"
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 19,
                    "candidatesTokenCount": 10,
                    "totalTokenCount": 245,
                    "promptTokensDetails": [
                      {"modality": "TEXT", "tokenCount": 19}
                    ],
                    "thoughtsTokenCount": 216
                  },
                  "modelVersion": "gemini-3-flash-preview",
                  "responseId": "test-response-id"
                }
                """);

        assertEquals(
                "{\"action\":\"HOLD\",\"reason\":\"smoke test\"}",
                client.generateJson("return json")
        );
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

    private static class RawGeminiClient extends GeminiClient {
        private final String rawResponse;

        private RawGeminiClient(String rawResponse) {
            super("test-api-key");
            this.rawResponse = rawResponse;
        }

        @Override
        public String postRaw(GeminiGenerateReq req) {
            return rawResponse;
        }
    }
}
