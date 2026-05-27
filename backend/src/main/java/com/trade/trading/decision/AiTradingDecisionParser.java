package com.trade.trading.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingAction;

import java.math.BigDecimal;

/**
 * Legacy OKX AI model output parser retained only for historical tests/data
 * tools. The runtime OKX trading path no longer parses AI responses.
 *
 * <p>Invalid non-HOLD payloads are downgraded to HOLD with the raw response
 * attached, so downstream execution can remain conservative.</p>
 */
public class AiTradingDecisionParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiTradingDecision parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return AiTradingDecision.hold("Invalid AI decision: empty response", rawResponse);
        }

        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(rawResponse));
            TradingAction action = parseAction(root.path("action").asText(null));
            if (action == null) {
                return AiTradingDecision.hold(
                        "Invalid AI decision: action must be BUY, HOLD, SELL, OPEN_LONG, CLOSE_LONG, OPEN_SHORT, or CLOSE_SHORT",
                        rawResponse
                );
            }

            String reason = root.path("reason").asText(null);
            if (reason == null || reason.isBlank()) {
                return AiTradingDecision.hold("Invalid AI decision: reason is required", rawResponse);
            }

            AiTradingDecision decision = new AiTradingDecision()
                    .setAction(action)
                    .setReason(reason)
                    .setOrderSize(readDecimal(root, "orderSize"))
                    .setWinProbability(readDecimal(root, "winProbability"))
                    .setConfidence(readDecimal(root, "confidence"))
                    .setObjectiveAlignment(readUpperText(root, "objectiveAlignment"))
                    .setExpectedNetEdgePercent(readDecimal(root, "expectedNetEdgePercent"))
                    .setRiskRewardRatio(readDecimal(root, "riskRewardRatio"))
                    .setThesisChangeEvidence(readText(root, "thesisChangeEvidence"))
                    .setStrategyBias(readText(root, "strategyBias"))
                    .setStrategyThesis(readText(root, "strategyThesis"))
                    .setStrategyInvalidation(readText(root, "strategyInvalidation"))
                    .setStrategyHorizon(readText(root, "strategyHorizon"))
                    .setRawResponse(rawResponse);

            if (action == TradingAction.BUY) {
                String validationError = validateProbabilityFields(decision);
                if (validationError != null) {
                    return AiTradingDecision.hold(validationError, rawResponse);
                }
                BigDecimal amount = readPositiveDecimal(root, "buyQuoteAmountUsdt");
                if (amount == null) {
                    return AiTradingDecision.hold("Invalid AI decision: BUY requires positive buyQuoteAmountUsdt", rawResponse);
                }
                decision.setBuyQuoteAmountUsdt(amount);
            } else if (action == TradingAction.SELL) {
                String validationError = validateProbabilityFields(decision);
                if (validationError != null) {
                    return AiTradingDecision.hold(validationError, rawResponse);
                }
                BigDecimal amount = readPositiveDecimal(root, "sellBaseAmountBtc");
                if (amount == null) {
                    return AiTradingDecision.hold("Invalid AI decision: SELL requires positive sellBaseAmountBtc", rawResponse);
                }
                decision.setSellBaseAmountBtc(amount);
            } else if (action.isDerivativeAction()) {
                String validationError = validateProbabilityFields(decision);
                if (validationError != null) {
                    return AiTradingDecision.hold(validationError, rawResponse);
                }
                BigDecimal amount = readPositiveDecimal(root, "orderSize");
                if (amount == null) {
                    return AiTradingDecision.hold("Invalid AI decision: " + action + " requires positive orderSize", rawResponse);
                }
                decision.setOrderSize(amount);
            }

            return decision;
        } catch (Exception e) {
            return AiTradingDecision.hold("Invalid AI decision: " + e.getMessage(), rawResponse);
        }
    }

    private static String validateProbabilityFields(AiTradingDecision decision) {
        if (!isInUnitInterval(decision.getWinProbability())) {
            return "Invalid AI decision: winProbability must be between 0 and 1 for non-HOLD actions";
        }
        if (!isInUnitInterval(decision.getConfidence())) {
            return "Invalid AI decision: confidence must be between 0 and 1 for non-HOLD actions";
        }
        return null;
    }

    private static TradingAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        try {
            String normalized = action.trim().toUpperCase()
                    .replace('-', '_')
                    .replace(' ', '_');
            return TradingAction.valueOf(normalized);
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

    private static String readUpperText(JsonNode root, String fieldName) {
        String value = readText(root, fieldName);
        return value == null ? null : value.toUpperCase();
    }

    private static BigDecimal readPositiveDecimal(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }

        try {
            BigDecimal value = new BigDecimal(node.asText().trim());
            return value.signum() > 0 ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
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

    private static boolean isInUnitInterval(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
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
        // Recover from occasional prose around the JSON object without trying
        // to parse arbitrary text as a decision.
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
