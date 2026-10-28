package com.trade.trading.application.strategy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.trading.domain.model.TradingAiProposal;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Treat model output as untrusted input, independently of prompt instructions. */
public final class TradingAiPolicy {
    public static final Set<String> TUNABLE = Set.of(
            "price-move-trigger-percent", "volume-spike-multiplier", "price-move-window-candles",
            "volume-lookback-candles", "ema-fast-period", "ema-slow-period", "rsi-period", "atr-period",
            "adx-period", "bollinger-period", "breakout-period", "volume-period", "bollinger-deviations",
            "trend-min-adx", "range-max-adx", "entry-rsi-min", "entry-rsi-max", "min-volume-ratio",
            "max-entry-extension-atr", "min-atr-percent", "max-atr-percent");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private TradingAiPolicy() {}

    public static TradingAiProposal parse(String response) {
        try {
            if (response == null || response.length() > 16000) throw new IllegalArgumentException();
            TradingAiProposal p = JSON.readValue(response, TradingAiProposal.class);
            if (p == null || p.action() == null || p.parameters() == null || p.parameters().size() > 6
                    || p.confidence() == null || p.confidence().signum() < 0
                    || p.confidence().compareTo(BigDecimal.ONE) > 0
                    || p.strategyId() == null || p.strategyId().length() > 128
                    || !text(p.reason(), 1000) || !text(p.marketRegime(), 100)
                    || p.logicSuggestion() == null || p.logicSuggestion().length() > 1800
                    || (p.action() != TradingAiProposal.Action.TUNE && !p.parameters().isEmpty())
                    || (p.action() == TradingAiProposal.Action.TUNE && p.parameters().isEmpty())) {
                throw new IllegalArgumentException();
            }
            return p;
        } catch (Exception e) {
            throw new IllegalArgumentException("AI response must match the bounded strategy proposal schema");
        }
    }

    public static Map<String, Object> merge(Map<String, Object> previous, Map<String, Object> changes) {
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        for (var entry : changes.entrySet()) {
            String key = entry.getKey();
            if (!TUNABLE.contains(key) || !previous.containsKey(key)
                    || !(entry.getValue() instanceof Number) || !(previous.get(key) instanceof Number)) {
                throw new IllegalArgumentException("AI cannot edit this parameter: " + key);
            }
            BigDecimal before = new BigDecimal(previous.get(key).toString());
            BigDecimal after = new BigDecimal(entry.getValue().toString());
            if (before.signum() <= 0 || after.signum() <= 0 || after.scale() > 18
                    || after.subtract(before).abs().compareTo(before.multiply(new BigDecimal("0.20"))) > 0) {
                throw new IllegalArgumentException("AI parameter changes must stay within 20 percent: " + key);
            }
            merged.put(key, entry.getValue());
        }
        return merged;
    }

    private static boolean text(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }
}
