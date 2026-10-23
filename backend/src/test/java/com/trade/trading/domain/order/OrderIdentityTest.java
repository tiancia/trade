package com.trade.trading.domain.order;

import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OrderIdentityTest {
    @Test
    void preservesVersionOneFingerprintAndClientOrderId() {
        var identity = new OrderIdentity();
        var decision = new StrategyDecision().setStrategyId("trend").setAction(TradingAction.BUY)
                .setBuyQuoteAmount(new BigDecimal("100.00"));
        var snapshot = new OrderIdentity.Snapshot(List.of(
                new OrderIdentity.CandleIdentity("1800000000000", false),
                new OrderIdentity.CandleIdentity("1700000000000", true)), List.of(), "ticker", "decision");
        String key = identity.create("BTC-USDT", decision, snapshot, "100");
        assertEquals("45dcd926c9d476289ae7723d2bec89dd28273275f877b4a4cbff589e639c1d35", key);
        assertEquals("stbu45dcd926c9d476289ae7723d2bec", identity.clientOrderId(key, "BUY"));
        assertNotEquals(key, identity.create("BTC-USDT", decision, snapshot, "101"));
    }

    @Test
    void confirmedSignalIdentityIgnoresOrderSizeAndFasterMarketSnapshots() {
        var identity = new OrderIdentity();
        var decision = signalDecision(1700000000000L);
        String key = identity.create("BTC-USDT", decision, snapshot("1700000000000"), "10");
        decision.setBuyQuoteAmount(new BigDecimal("20")).setOrderSize(new BigDecimal("2"));
        decision.setMetadata(Map.of("signalBar", "15m", "signalCandleTs", "1700000000000",
                "configurationVersion", 2));
        assertEquals(key, identity.create("BTC-USDT", decision, snapshot("1700000060000"), "20"));
        assertEquals(64, key.length());
        assertTrue(identity.clientOrderId(key, "BUY").length() <= 32);
        assertNotEquals(key, identity.create("BTC-USDT", signalDecision(1700000900000L), snapshot("1700000060000"), "10"));
        assertNotEquals(key, identity.create("ETH-USDT", decision, snapshot("1700000000000"), "10"));
        decision.setStrategyId("another-strategy");
        assertNotEquals(key, identity.create("BTC-USDT", decision, snapshot("1700000000000"), "10"));
        decision.setStrategyId("intraday").setAction(TradingAction.SELL);
        assertNotEquals(key, identity.create("BTC-USDT", decision, snapshot("1700000000000"), "10"));
    }

    @Test
    void paperSignalsAreStableWithinOneSessionAndIsolatedAcrossSessions() {
        var identity = new OrderIdentity();
        var metadata = signalDecision(1700000000000L).getMetadata();
        String first = identity.paperDecisionId("BTC-USDT", "session-one", "intraday", "BUY", metadata, "random-one");
        assertEquals(first, identity.paperDecisionId("BTC-USDT", "session-one", "intraday", "BUY", metadata, "random-two"));
        assertNotEquals(first, identity.paperDecisionId("BTC-USDT", "session-two", "intraday", "BUY", metadata, "random-one"));
        assertEquals(64, first.length());
        assertEquals("legacy-random", identity.paperDecisionId("BTC-USDT", "session-one", "threshold", "BUY",
                Map.of("bar", "1m"), "legacy-random"));
        assertThrows(IllegalArgumentException.class, () -> identity.paperDecisionId("BTC-USDT", null, "intraday", "BUY", metadata, "random"));
    }

    @Test
    void malformedSignalIdentityNeverFallsBackToMutableMarketIdentity() {
        var identity = new OrderIdentity();
        for (Map<String, Object> metadata : List.<Map<String, Object>>of(
                Map.of("signalBar", "15m"),
                Map.of("signalCandleTs", 1700000000000L),
                Map.of("signalBar", "1m", "signalCandleTs", 1700000000000L),
                Map.of("signalBar", "15m", "signalCandleTs", 0),
                Map.of("signalBar", "15m", "signalCandleTs", -1),
                Map.of("signalBar", "15m", "signalCandleTs", "1.5"),
                Map.of("signalBar", "15m", "signalCandleTs", "9223372036854775808"),
                Map.of("signalBar", "15m", "signalCandleTs", true))) {
            var decision = signalDecision(1700000000000L).setMetadata(metadata);
            assertThrows(IllegalArgumentException.class,
                    () -> identity.create("BTC-USDT", decision, snapshot("1700000000000"), "10"));
        }
    }

    private static StrategyDecision signalDecision(long timestamp) {
        return new StrategyDecision().setStrategyId("intraday").setAction(TradingAction.BUY)
                .setBuyQuoteAmount(BigDecimal.TEN)
                .setMetadata(Map.of("signalBar", "15m", "signalCandleTs", timestamp));
    }

    private static OrderIdentity.Snapshot snapshot(String timestamp) {
        return new OrderIdentity.Snapshot(List.of(new OrderIdentity.CandleIdentity(timestamp, true)),
                List.of(), "ticker-" + timestamp, "random-" + timestamp);
    }
}
