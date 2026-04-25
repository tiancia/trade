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

    /**
     * 使用环境变量 GEMINI_API_KEY 初始化客户端，baseUrl 和 model 使用默认值。
     */
    public GeminiClient() {
        this(
                System.getenv("GEMINI_API_KEY"),
                DEFAULT_BASE_URL,
                DEFAULT_MODEL
        );
    }

    /**
     * 使用传入的 API Key 初始化客户端，其他参数使用默认值。
     */
    public GeminiClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    /**
     * 完整初始化 Gemini 客户端，适合需要自定义地址或模型时使用。
     */
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

    /**
     * 发送普通文本提示词，并返回 Gemini 生成的文本内容。
     */
    public String generateText(String prompt) {
        GeminiGenerateReq req = new GeminiGenerateReq()
                .setContents(List.of(
                        new GeminiContent().setParts(List.of(
                                new GeminiPart().setText(prompt)
                        ))
                ));
        return generateText(req);
    }

    /**
     * 发送提示词并要求 Gemini 按 JSON MIME 类型返回结果。
     */
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

    /**
     * 发送已组装好的 Gemini 请求，并从响应对象中提取文本。
     */
    public String generateText(GeminiGenerateReq req) {
        GeminiGenerateResp resp = generateContent(req);
        return extractText(resp);
    }

    /**
     * 调用 Gemini generateContent 接口，并把原始 JSON 响应反序列化为响应对象。
     */
    public GeminiGenerateResp generateContent(GeminiGenerateReq req) {
        String body = postRaw(req);
        try {
            return objectMapper.readValue(body, GeminiGenerateResp.class);
        } catch (Exception e) {
            throw new RuntimeException("Parse Gemini response error, body=" + body, e);
        }
    }

    /**
     * 执行实际 HTTP POST 请求，返回 Gemini 的原始响应字符串。
     */
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

    /**
     * 拼接当前模型的 generateContent 接口地址。
     */
    private String buildGenerateContentUrl() {
        return baseUrl + "/v1beta/models/" + model + ":generateContent";
    }

    /**
     * 从 Gemini 响应候选结果中合并所有 text part，异常响应会直接报错。
     */
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

    /**
     * 校验必填字符串，避免配置为空时继续发送请求。
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * 去掉 baseUrl 末尾多余的斜杠，避免拼接接口地址时出现双斜杠。
     */
    private static String trimRightSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
