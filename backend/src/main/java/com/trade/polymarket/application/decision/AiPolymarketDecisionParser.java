package com.trade.polymarket.application.decision;

import com.trade.polymarket.domain.rule.PolymarketDecisionRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.polymarket.domain.model.AiPolymarketDecision;
import com.trade.polymarket.domain.model.PolymarketAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Parses Polymarket model output and downgrades unsafe or incomplete BUY
 * payloads to HOLD decisions.
 */
@Component
public class AiPolymarketDecisionParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiPolymarketDecision parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return AiPolymarketDecision.hold("Invalid Polymarket AI decision: empty response", rawResponse);
        }

        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(rawResponse));
            PolymarketAction action = parseAction(root.path("action").asText(null));
            if (action == null) {
                return AiPolymarketDecision.hold(
                        "Invalid Polymarket AI decision: action must be BUY or HOLD",
                        rawResponse
                );
            }

            String reason = root.path("reason").asText(null);
            if (reason == null || reason.isBlank()) {
                return AiPolymarketDecision.hold(
                        "Invalid Polymarket AI decision: reason is required",
                        rawResponse
                );
            }

            BigDecimal winProbability = firstNonNull(
                    readDecimal(root, "winProbability"),
                    readDecimal(root, "estimatedProbability")
            );
            // Older prompts used estimatedProbability; keep accepting it as an
            // alias so stored prompts/tests do not break during upgrades.
            BigDecimal estimatedProbability = firstNonNull(readDecimal(root, "estimatedProbability"), winProbability);

            AiPolymarketDecision decision = new AiPolymarketDecision()
                    .setAction(action)
                    .setReason(reason)
                    .setMarketId(readText(root, "marketId"))
                    .setMarketSlug(readText(root, "marketSlug"))
                    .setMarketQuestion(readText(root, "marketQuestion"))
                    .setOutcome(readText(root, "outcome"))
                    .setTokenId(readText(root, "tokenId"))
                    .setLimitPrice(readDecimal(root, "limitPrice"))
                    .setMaxSpendUsdc(readDecimal(root, "maxSpendUsdc"))
                    .setWinProbability(winProbability)
                    .setConfidence(readDecimal(root, "confidence"))
                    .setEstimatedProbability(estimatedProbability)
                    .setEstimatedEdge(readDecimal(root, "estimatedEdge"))
                    .setRawResponse(rawResponse);

            if (action == PolymarketAction.BUY) {
                String validationError = PolymarketDecisionRules.validateBuy(decision);
                if (validationError != null) {
                    return AiPolymarketDecision.hold(validationError, rawResponse);
                }
            }
            return decision;
        } catch (Exception e) {
            return AiPolymarketDecision.hold("Invalid Polymarket AI decision: " + e.getMessage(), rawResponse);
        }
    }

    private static PolymarketAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        try {
            return PolymarketAction.valueOf(action.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String readText(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal readDecimal(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first == null ? second : first;
    }

    private static String extractJsonObject(String rawResponse) {
        String text = rawResponse.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd >= 0) {
                text = text.substring(firstLineEnd + 1).trim();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        // Recover JSON wrapped in markdown or short prose without accepting
        // responses that contain no object at all.
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
