package com.trade.trading.domain.order;

import com.trade.trading.domain.model.StrategyDecision;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Builds a stable business fingerprint and the deterministic OKX clOrdId. */
public class OrderIdentity {
    public record CandleIdentity(String timestamp, boolean confirmed) {}
    public record Snapshot(List<CandleIdentity> oneMinuteCandles, List<CandleIdentity> fiveMinuteCandles,
                           String tickerTimestamp, String decisionId) {}

    public String create(
            String instrumentId,
            StrategyDecision decision,
            Snapshot snapshot,
            String requestedSize
    ) {
        String source = String.join("|",
                "v1",
                text(instrumentId),
                text(decision == null ? null : decision.getStrategyId()),
                text(decision == null || decision.getAction() == null ? null : decision.getAction().name()),
                marketSnapshotIdentity(snapshot),
                decimal(decision == null ? null : decision.getBuyQuoteAmount()),
                decimal(decision == null ? null : decision.getSellBaseAmount()),
                decimal(decision == null ? null : decision.getOrderSize()),
                text(requestedSize)
        );
        return sha256(source);
    }

    public String clientOrderId(String idempotencyKey, String action) {
        String actionCode = text(action).toLowerCase().replaceAll("[^a-z]", "");
        if (actionCode.length() > 2) {
            actionCode = actionCode.substring(0, 2);
        }
        String prefix = "st" + actionCode;
        int hashLength = Math.min(32 - prefix.length(), idempotencyKey.length());
        return prefix + idempotencyKey.substring(0, hashLength);
    }

    private static String marketSnapshotIdentity(Snapshot snapshot) {
        String candleTs = firstConfirmedCandleTs(snapshot.oneMinuteCandles());
        if (candleTs == null) {
            candleTs = firstConfirmedCandleTs(snapshot.fiveMinuteCandles());
        }
        if (candleTs != null) {
            return "candle:" + candleTs;
        }
        if (hasText(snapshot.tickerTimestamp())) {
            return "ticker:" + snapshot.tickerTimestamp();
        }
        return "decision:" + text(snapshot.decisionId());
    }

    private static String firstConfirmedCandleTs(List<CandleIdentity> candles) {
        if (candles == null) {
            return null;
        }
        for (CandleIdentity candle : candles) {
            if (candle != null && candle.confirmed() && hasText(candle.timestamp())) {
                return candle.timestamp();
            }
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
