package com.trade.polymarket.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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
                    .setConfidence(readDecimal(root, "confidence"))
                    .setEstimatedProbability(readDecimal(root, "estimatedProbability"))
                    .setEstimatedEdge(readDecimal(root, "estimatedEdge"))
                    .setRawResponse(rawResponse);

            if (action == PolymarketAction.BUY) {
                String validationError = validateBuy(decision);
                if (validationError != null) {
                    return AiPolymarketDecision.hold(validationError, rawResponse);
                }
            }
            return decision;
        } catch (Exception e) {
            return AiPolymarketDecision.hold("Invalid Polymarket AI decision: " + e.getMessage(), rawResponse);
        }
    }

    private static String validateBuy(AiPolymarketDecision decision) {
        if (!hasText(decision.getTokenId())) {
            return "Invalid Polymarket AI decision: BUY requires tokenId";
        }
        if (!hasText(decision.getOutcome())) {
            return "Invalid Polymarket AI decision: BUY requires outcome";
        }
        if (!isPositive(decision.getLimitPrice())) {
            return "Invalid Polymarket AI decision: BUY requires positive limitPrice";
        }
        if (decision.getLimitPrice().compareTo(BigDecimal.ONE) > 0) {
            return "Invalid Polymarket AI decision: limitPrice must be <= 1";
        }
        if (!isPositive(decision.getMaxSpendUsdc())) {
            return "Invalid Polymarket AI decision: BUY requires positive maxSpendUsdc";
        }
        if (!isInUnitInterval(decision.getConfidence())) {
            return "Invalid Polymarket AI decision: confidence must be between 0 and 1";
        }
        if (!isInUnitInterval(decision.getEstimatedProbability())) {
            return "Invalid Polymarket AI decision: estimatedProbability must be between 0 and 1";
        }
        if (decision.getEstimatedEdge() == null) {
            return "Invalid Polymarket AI decision: estimatedEdge is required";
        }
        return null;
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
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean isInUnitInterval(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
