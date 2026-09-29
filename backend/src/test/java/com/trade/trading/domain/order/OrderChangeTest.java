package com.trade.trading.domain.order;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class OrderChangeTest {
    @Test
    void publicApiCannotMutateOrdersOrInjectArbitraryChanges() {
        assertEquals(0, TradingOrder.class.getConstructors().length);
        assertEquals(0, OrderChange.class.getConstructors().length);
        assertFalse(java.util.Arrays.stream(TradingOrder.class.getMethods()).anyMatch(m -> m.getName().startsWith("set")));
        assertFalse(java.util.Arrays.stream(com.trade.trading.domain.model.TradingPositionState.class.getMethods())
                .anyMatch(m -> m.getName().startsWith("set")));
        var order = TradingOrder.restore(TradingOrder.Snapshot.builder().status(OrderStatus.ACCEPTED).build());
        order.snapshot().toBuilder().status(OrderStatus.FILLED).build();
        assertEquals(OrderStatus.ACCEPTED, order.getStatus());
    }

    @Test
    void candidateDoesNotMutateOriginalAndDuplicatePreservesCompletionTime() {
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        var original = TradingOrder.restore(TradingOrder.Snapshot.builder().status(OrderStatus.ACCEPTED).version(3).build());
        var fill = new OrderFill(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, "USDT");
        var filled = OrderChange.markFilled("exchange", fill, now).apply(original, now);
        assertEquals(OrderStatus.ACCEPTED, original.getStatus());
        assertNull(original.getCompletedAt());
        assertEquals(4, filled.getVersion());
        var repeated = OrderChange.markFilled("exchange", fill, now.plusSeconds(10)).apply(filled, now.plusSeconds(10));
        assertTrue(OrderChange.hasSameExchangeState(filled, repeated));
        assertEquals(now, repeated.getCompletedAt());
        assertThrows(IllegalStateException.class, () -> OrderChange.markAccepted("exchange", now).apply(filled, now));
    }
}
