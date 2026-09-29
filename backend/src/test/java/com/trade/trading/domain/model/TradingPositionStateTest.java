package com.trade.trading.domain.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class TradingPositionStateTest {
    @Test
    void updatesAreImmutableAndStrictFillsCannotOversell() {
        var original = TradingPositionState.seed("live", "BTC-USDT", n("2"), n("100"));
        var bought = original.buy(n("1"), n("130"));
        assertDecimal("2", original.getQuantity());
        assertDecimal("3", bought.getQuantity());
        assertDecimal("110", bought.getAverageCost());
        assertEquals(1, bought.getVersion());
        var sold = bought.applySellFill(n("1"));
        assertDecimal("110", sold.getAverageCost());
        assertThrows(IllegalStateException.class, () -> sold.applySellFill(n("3")));
        assertDecimal("2", sold.getQuantity());
        assertDecimal("0", sold.applySellFill(n("2")).getAverageCost());
        assertDecimal("0", sold.sell(n("3")).getQuantity());
        assertThrows(IllegalStateException.class, () -> original.buy(n("0"), n("100")));
    }

    @Test
    void exchangeObservationDoesNotReplaceManagedPositionWithoutExplicitAuthority() {
        var original = TradingPositionState.seed("live", "BTC-USDT", n("2"), n("100"));
        var observed = original.reconcile(n("9"), null, null, Instant.EPOCH);
        assertDecimal("2", observed.getQuantity());
        assertDecimal("9", observed.getExchangeQuantity());
        assertEquals(1, observed.getVersion());
        var authoritative = observed.reconcile(n("4"), n("4"), null, Instant.EPOCH);
        assertDecimal("4", authoritative.getQuantity());
        assertDecimal("100", authoritative.getAverageCost());
        assertDecimal("0", authoritative.reconcile(n("0"), n("0"), null, Instant.EPOCH).getAverageCost());
    }

    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static void assertDecimal(String expected, BigDecimal actual) { assertEquals(0, n(expected).compareTo(actual)); }
}
