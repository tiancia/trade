package com.trade.client.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.gemini.dto.GeminiContent;
import com.trade.client.gemini.dto.GeminiGenerateReq;
import com.trade.client.gemini.dto.GeminiGenerateResp;
import com.trade.client.gemini.dto.GeminiGenerationConfig;
import com.trade.client.gemini.dto.GeminiPart;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class GeminiClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public GeminiClient() {
        this(new GeminiClientProperties());
    }

    public GeminiClient(String apiKey) {
        this(apiKey, "https://generativelanguage.googleapis.com", "gemini-3-flash-preview");
    }

    public GeminiClient(String apiKey, String baseUrl, String model) {
        this(properties(apiKey, baseUrl, model));
    }

    public GeminiClient(GeminiClientProperties properties) {
        this.httpClient = buildHttpClient(properties);
        this.objectMapper = new ObjectMapper()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        this.apiKey = properties.requiredApiKey();
        this.baseUrl = properties.normalizedBaseUrl();
        this.model = properties.requiredModel();
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

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new RuntimeException("Gemini HTTP error, status=" + statusCode + ", body=" + response.body());
            }
            return response.body();
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

    static HttpClient buildHttpClient(GeminiClientProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30));
        GeminiClientProperties.ProxyProperties proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }

    private static GeminiClientProperties properties(String apiKey, String baseUrl, String model) {
        GeminiClientProperties properties = new GeminiClientProperties();
        properties.setApiKey(apiKey);
        properties.setBaseUrl(baseUrl);
        properties.setModel(model);
        return properties;
    }
}
