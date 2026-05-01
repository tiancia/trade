package com.trade.client.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.ai.AiClientProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleAiTextClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsJsonChatCompletionRequestAndExtractsAssistantContent() throws Exception {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.OPENAI_COMPATIBLE);
        properties.setModel("deepseek-chat");
        properties.setTemperature(0.1);
        properties.setMaxOutputTokens(512);

        CapturingClient client = new CapturingClient(properties, """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "{\\"action\\":\\"HOLD\\",\\"reason\\":\\"test\\"}"
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """);

        String result = client.generateJson("return decision json");

        assertEquals("{\"action\":\"HOLD\",\"reason\":\"test\"}", result);

        JsonNode request = objectMapper.readTree(client.body);
        assertEquals("deepseek-chat", request.path("model").asText());
        assertEquals("user", request.path("messages").get(0).path("role").asText());
        assertEquals("return decision json", request.path("messages").get(0).path("content").asText());
        assertEquals("json_object", request.path("response_format").path("type").asText());
        assertEquals(0, Double.compare(0.1, request.path("temperature").asDouble()));
        assertEquals(512, request.path("max_tokens").asInt());
    }

    @Test
    void extractsAssistantContentFromContentPartsArray() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.OPENAI_COMPATIBLE);
        properties.setBaseUrl("https://api.example.com");
        properties.setModel("example-model");
        properties.setApiKey("test-key");

        CapturingClient client = new CapturingClient(properties, """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": [
                          {"type": "text", "text": "{\\"action\\":\\"HOLD\\","},
                          {"type": "text", "text": "\\"reason\\":\\"array content\\"}"}
                        ]
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """);

        String result = client.generateJson("return decision json");

        assertEquals("{\"action\":\"HOLD\",\"reason\":\"array content\"}", result);
    }

    @Test
    void responseWithoutAssistantContentKeepsSpecificFailureReason() {
        AiClientProperties properties = new AiClientProperties();
        properties.setProvider(AiClientProperties.Provider.OPENAI_COMPATIBLE);
        properties.setBaseUrl("https://api.example.com");
        properties.setModel("example-model");
        properties.setApiKey("test-key");

        CapturingClient client = new CapturingClient(properties, """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": ""
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> client.generateJson("return decision json")
        );

        assertTrue(error.getMessage().contains("OpenAI-compatible response has no content, finishReason=stop"));
        assertTrue(error.getMessage().contains("contentNodeType=STRING"));
    }

    private static class CapturingClient extends OpenAiCompatibleAiTextClient {
        private final String rawResponse;
        private String body;

        private CapturingClient(AiClientProperties properties, String rawResponse) {
            super(properties);
            this.rawResponse = rawResponse;
        }

        @Override
        String postRaw(String body) {
            this.body = body;
            return rawResponse;
        }
    }
}
