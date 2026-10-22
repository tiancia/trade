package com.trade.trading.application.market;

import com.trade.client.okx.dto.CandleResp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleFreshnessTest {
    private static final Instant NOW = Instant.parse("2026-10-22T08:04:20Z");

    @Test
    void requiresLatestCandleToBelongToCurrentIntervalForEveryChartBar() {
        for (String bar : List.of("1m", "5m", "15m", "1H", "4H")) {
            long interval = CandleFreshness.intervalMillis(bar);
            long current = NOW.toEpochMilli() / interval * interval;
            assertTrue(CandleFreshness.isCurrent(List.of(candle(current)), bar, NOW), bar);
            assertFalse(CandleFreshness.isCurrent(List.of(candle(current - interval)), bar, NOW), bar);
            assertFalse(CandleFreshness.isCurrent(List.of(candle(current)), bar,
                    Instant.ofEpochMilli(current + interval)), bar);
        }
    }

    @Test
    void ignoresMalformedAndFutureCandlesWithoutAcceptingAnOldHistory() {
        CandleResp invalid = new CandleResp();
        invalid.setTs("not-a-timestamp");
        long current = NOW.toEpochMilli() / 60_000L * 60_000L;
        assertFalse(CandleFreshness.isCurrent(Arrays.asList(null, invalid, candle(-1),
                candle(current - 60_000L), candle(current + 60_000L)), "1m", NOW));
        assertTrue(CandleFreshness.isCurrent(Arrays.asList(null, invalid, candle(current),
                candle(current + 60_000L)), "1m", NOW));
        assertFalse(CandleFreshness.isCurrent(List.of(candle(current)), "unknown", NOW));
        assertFalse(CandleFreshness.isCurrent(List.of(), "1m", NOW));
    }

    private static CandleResp candle(long timestamp) {
        CandleResp candle = new CandleResp();
        candle.setTs(String.valueOf(timestamp));
        return candle;
    }
}
