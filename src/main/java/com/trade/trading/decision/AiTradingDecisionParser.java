package com.trade.trading.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
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
                return AiTradingDecision.hold("Invalid AI decision: action must be BUY, HOLD, or SELL", rawResponse);
            }

            String reason = root.path("reason").asText(null);
            if (reason == null || reason.isBlank()) {
                return AiTradingDecision.hold("Invalid AI decision: reason is required", rawResponse);
            }

            AiTradingDecision decision = new AiTradingDecision()
                    .setAction(action)
                    .setReason(reason)
                    .setRawResponse(rawResponse);

            if (action == TradingAction.BUY) {
                BigDecimal amount = readPositiveDecimal(root, "buyQuoteAmountUsdt");
                if (amount == null) {
                    return AiTradingDecision.hold("Invalid AI decision: BUY requires positive buyQuoteAmountUsdt", rawResponse);
                }
                decision.setBuyQuoteAmountUsdt(amount);
            } else if (action == TradingAction.SELL) {
                BigDecimal amount = readPositiveDecimal(root, "sellBaseAmountBtc");
                if (amount == null) {
                    return AiTradingDecision.hold("Invalid AI decision: SELL requires positive sellBaseAmountBtc", rawResponse);
                }
                decision.setSellBaseAmountBtc(amount);
            }

            return decision;
        } catch (Exception e) {
            return AiTradingDecision.hold("Invalid AI decision: " + e.getMessage(), rawResponse);
        }
    }

    private static TradingAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        try {
            return TradingAction.valueOf(action.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
