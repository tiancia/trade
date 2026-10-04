package com.trade.x.application.decision;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.ai.AiTextClient;
import com.trade.x.application.port.XDraftGenerator;
import com.trade.x.domain.model.GeneratedXPost;
import com.trade.x.domain.model.XContentPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.List;

@Component
public class XAiDraftGenerator implements XDraftGenerator {
    private final AiTextClient ai;
    private final ObjectMapper json;

    @Autowired
    public XAiDraftGenerator(AiTextClient ai) {
        this(ai, new ObjectMapper().findAndRegisterModules());
    }

    public XAiDraftGenerator(AiTextClient ai, ObjectMapper json) {
        this.ai = Objects.requireNonNull(ai, "AI text client is required");
        this.json = Objects.requireNonNull(json, "X draft JSON mapper is required");
    }

    @Override
    public GeneratedXPost generate(XContentPolicy content, List<String> recentBodies) {
        Objects.requireNonNull(content, "X content policy is required");
        if (content.direction().isBlank()) throw new IllegalArgumentException("X content direction is required");
        try {
            List<String> recent = recentBodies == null ? List.of() : recentBodies.stream()
                    .filter(Objects::nonNull).filter(body -> !body.isBlank())
                    .filter(body -> body.codePointCount(0, body.length()) <= 280).limit(8).toList();
            String prompt = XDraftPromptBuilder.build(content, recent, json);
            String raw = ai.generateJson(prompt);
            if (raw == null || raw.isBlank() || raw.length() > 40000) {
                throw new IllegalArgumentException("Invalid X AI response");
            }
            JsonNode output = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (output == null || !output.isObject()) {
                throw new IllegalArgumentException("Invalid X AI response");
            }
            // Accept the previous single-draft protocol during provider rollout.
            JsonNode candidates = output.get("candidates");
            if (candidates == null) return candidate(output, content, recent);
            if (!candidates.isArray() || candidates.isEmpty() || candidates.size() > 3) {
                throw new IllegalArgumentException("Invalid X candidates");
            }
            for (JsonNode value : candidates) {
                try {
                    return candidate(value, content, recent);
                } catch (IllegalArgumentException invalidCandidate) {
                    // Try another already-generated candidate, never another paid request.
                }
            }
            throw new IllegalArgumentException("No usable X candidate");
        } catch (Exception failure) {
            // Supplier responses and nested exceptions can contain credentials; expose a stable safe message.
            throw new IllegalStateException("X AI draft generation failed");
        }
    }

    private GeneratedXPost candidate(JsonNode value, XContentPolicy content, List<String> recent) {
        if (!value.isObject() || !value.path("body").isTextual() || !value.path("reviewNote").isTextual()) {
            throw new IllegalArgumentException("Invalid X candidate");
        }
        String body = XPostTextValidator.normalizeAndValidate(value.path("body").textValue(), content);
        if (XContentPolicy.repeatsRecentBody(body, recent)) throw new IllegalArgumentException("Repeated X candidate");
        return new GeneratedXPost(body, value.path("reviewNote").textValue());
    }
}
