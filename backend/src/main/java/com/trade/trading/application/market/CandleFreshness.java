package com.trade.trading.application.market;

import com.trade.client.okx.dto.CandleResp;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Distinguishes a retained candle history from a current market observation. */
public final class CandleFreshness {
    private CandleFreshness() {
    }

    public static boolean isCurrent(List<CandleResp> candles, String bar, Instant now) {
        long interval = intervalMillis(bar);
        if (candles == null || interval <= 0 || now == null) {
            return false;
        }
        long nowMillis = now.toEpochMilli();
        long latest = candles.stream()
                .mapToLong(CandleFreshness::timestampMillis)
                .filter(timestamp -> timestamp > 0 && timestamp <= nowMillis)
                .max()
                .orElse(0L);
        return latest >= Math.floorDiv(nowMillis, interval) * interval && latest > 0;
    }

    public static long latestTimestampMillis(List<CandleResp> candles) {
        return candles == null ? 0L : candles.stream()
                .mapToLong(CandleFreshness::timestampMillis)
                .max()
                .orElse(0L);
    }

    public static long timestampMillis(CandleResp candle) {
        if (candle == null || candle.getTs() == null) {
            return 0L;
        }
        try {
            return Math.max(Long.parseLong(candle.getTs()), 0L);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public static long intervalMillis(String bar) {
        return switch (bar == null ? "" : bar.trim().toLowerCase(Locale.ROOT)) {
            case "1m" -> 60_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "1h" -> 3_600_000L;
            case "4h" -> 14_400_000L;
            default -> 0L;
        };
    }
}
