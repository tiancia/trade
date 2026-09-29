package com.trade.trading.domain.order;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class PositionAccountingTest {
    @Test
    void duplicateAndRegressingCumulativeSnapshotsHaveNoDelta() {
        assertFalse(PositionAccounting.cumulativeDelta(n("2"), n("1.98"), n("200"), n("2"), n("1.98"), n("200")).changed());
        assertFalse(PositionAccounting.cumulativeDelta(n("1"), n("0.99"), n("100"), n("2"), n("1.98"), n("200")).changed());
        assertFalse(PositionAccounting.cumulativeDelta(n("3"), n("2.97"), n("190"), n("2"), n("1.98"), n("200")).changed());
        var delta = PositionAccounting.cumulativeDelta(n("3"), n("2.97"), n("330"), n("2"), n("1.98"), n("200"));
        assertTrue(delta.changed());
        assertEquals(n("0.99"), delta.quantity());
        assertEquals(n("130"), delta.quoteCost());
    }

    @Test
    void weightedCostSurvivesPartialSellAndClearsOnFullSell() {
        var position = PositionAccounting.buy(n("2"), n("100"), n("1"), n("130"));
        assertEquals(0, n("110").compareTo(position.averageCost()));
        var partial = PositionAccounting.sell(position.quantity(), position.averageCost(), n("1"), true);
        assertEquals(position.averageCost(), partial.averageCost());
        assertThrows(IllegalStateException.class, () -> PositionAccounting.sell(n("2"), n("110"), n("3"), true));
        assertEquals(BigDecimal.ZERO, PositionAccounting.sell(n("2"), n("110"), n("2"), true).averageCost());
    }

    @Test
    void buyNeedsPositiveQuantityAndCostDeltas() {
        assertThrows(IllegalStateException.class, () -> PositionAccounting.requireMonotonicDelta("buy", n("1"), n("0")));
        assertThrows(IllegalStateException.class, () -> PositionAccounting.requireMonotonicDelta("sell", n("0"), n("1")));
    }

    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
