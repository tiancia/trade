package com.trade.trading.order;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Builds a stable business fingerprint and the deterministic OKX clOrdId. */
@Component
public class OrderIdempotencyKeyFactory {
    public String create(
            TradingProperties properties,
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord record,
            String requestedSize
    ) {
        String source = String.join("|",
                "v1",
                text(properties.getInstId()),
                text(decision == null ? null : decision.getStrategyId()),
                text(decision == null || decision.getAction() == null ? null : decision.getAction().name()),
                marketSnapshotIdentity(context, record),
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

    private static String marketSnapshotIdentity(TradingDecisionContext context, TradingDecisionRecord record) {
        String candleTs = firstConfirmedCandleTs(context == null ? null : context.getOneMinuteCandles());
        if (candleTs == null) {
            candleTs = firstConfirmedCandleTs(context == null ? null : context.getFiveMinuteCandles());
        }
        if (candleTs != null) {
            return "candle:" + candleTs;
        }
        if (context != null && context.getTicker() != null && hasText(context.getTicker().getTs())) {
            return "ticker:" + context.getTicker().getTs();
        }
        return "decision:" + text(record == null ? null : record.getDecisionId());
    }

    private static String firstConfirmedCandleTs(List<CandleResp> candles) {
        if (candles == null) {
            return null;
        }
        for (CandleResp candle : candles) {
            if (candle != null && "1".equals(candle.getConfirm()) && hasText(candle.getTs())) {
                return candle.getTs();
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
