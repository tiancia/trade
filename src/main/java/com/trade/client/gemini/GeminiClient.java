package com.trade.client.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.dto.ai.GeminiContent;
import com.trade.dto.ai.GeminiGenerateReq;
import com.trade.dto.ai.GeminiGenerateResp;
import com.trade.dto.ai.GeminiPart;
import com.trade.dto.ai.GeminiGenerationConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class GeminiClient {

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String DEFAULT_MODEL = "gemini-3-flash-preview";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public GeminiClient() {
        this(
                System.getenv("GEMINI_API_KEY"),
                getEnvOrDefault("GEMINI_BASE_URL", DEFAULT_BASE_URL),
                getEnvOrDefault("GEMINI_MODEL", DEFAULT_MODEL)
        );
    }

    public GeminiClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    public GeminiClient(String apiKey, String baseUrl, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        this.apiKey = requireText(apiKey, "GEMINI_API_KEY is required");
        this.baseUrl = trimRightSlash(requireText(baseUrl, "Gemini base url is required"));
        this.model = requireText(model, "Gemini model is required");
    }

    public String generateText(String prompt) {
        GeminiGenerateReq req = new GeminiGenerateReq()
                .setContents(List.of(
                        new GeminiContent().setParts(List.of(
                                new GeminiPart().setText(prompt)
                        ))
                ));
        return generateText(req);
    }

    public String generateJson(String prompt) {
        GeminiGenerateReq req = new GeminiGenerateReq()
                .setContents(List.of(
                        new GeminiContent().setParts(List.of(
                                new GeminiPart().setText(prompt)
                        ))
                ))
                .setGenerationConfig(new GeminiGenerationConfig()
                        .setResponseMimeType("application/json"));
        return generateText(req);
    }

    public String generateText(GeminiGenerateReq req) {
        GeminiGenerateResp resp = generateContent(req);
        return extractText(resp);
    }

    public GeminiGenerateResp generateContent(GeminiGenerateReq req) {
        String body = postRaw(req);
        try {
            return objectMapper.readValue(body, GeminiGenerateResp.class);
        } catch (Exception e) {
            throw new RuntimeException("Parse Gemini response error, body=" + body, e);
        }
    }

    public String postRaw(GeminiGenerateReq req) {
        try {
            String body = objectMapper.writeValueAsString(req);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildGenerateContentUrl()))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            int statusCode = response.statusCode();
            String responseBody = response.body();
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException(
                        "Gemini HTTP error, status=" + statusCode + ", body=" + responseBody
                );
            }

            return responseBody;
        } catch (Exception e) {
            throw new RuntimeException("Send Gemini request error", e);
        }
    }

    private String buildGenerateContentUrl() {
        return baseUrl + "/v1beta/models/" + model + ":generateContent";
    }

    private String extractText(GeminiGenerateResp resp) {
        if (resp == null || resp.getCandidates() == null || resp.getCandidates().isEmpty()) {
            throw new RuntimeException("Gemini response has no candidates");
        }

        GeminiGenerateResp.Candidate candidate = resp.getCandidates().getFirst();
        if (candidate.getContent() == null
                || candidate.getContent().getParts() == null
                || candidate.getContent().getParts().isEmpty()) {
            throw new RuntimeException(
                    "Gemini response has no text, finishReason=" + candidate.getFinishReason()
            );
        }

        StringBuilder text = new StringBuilder();
        for (GeminiPart part : candidate.getContent().getParts()) {
            if (part.getText() != null) {
                text.append(part.getText());
            }
        }

        if (text.isEmpty()) {
            throw new RuntimeException(
                    "Gemini response text is empty, finishReason=" + candidate.getFinishReason()
            );
        }
        return text.toString();
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String trimRightSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
