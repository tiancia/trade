package com.trade.trading.domain.order;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class OrderSettlementPolicyTest {
    private final OrderSettlementPolicy policy = new OrderSettlementPolicy("BTC", "USDT");

    @Test
    void baseFeeReducesBuyQuantityAndIncreasesSellReduction() {
        var buy = local("buy");
        var observation = observed("buy", "filled", "-0.01", "BTC");
        var plan = policy.prepare(buy, observation);
        var amounts = policy.cumulativeSpotAmounts(buy, observation, plan.amounts());
        assertDecimal("0.99", amounts.positionQuantity());
        assertDecimal("100", amounts.quoteCost());
        var sell = local("sell");
        observation = observed("sell", "filled", "-0.01", "BTC");
        amounts = policy.cumulativeSpotAmounts(sell, observation, policy.prepare(sell, observation).amounts());
        assertDecimal("1.01", amounts.positionQuantity());
        assertDecimal("0", amounts.quoteCost());
    }

    @Test
    void quoteFeeIncreasesBuyCostWithoutReducingQuantity() {
        var order = local("buy");
        var observation = observed("buy", "partially_filled", "-2", "USDT");
        var amounts = policy.cumulativeSpotAmounts(order, observation, policy.prepare(order, observation).amounts());
        assertDecimal("1", amounts.positionQuantity());
        assertDecimal("102", amounts.quoteCost());
    }

    @Test
    void liveObservationDoesNotUndoPendingCancellation() {
        var order = TradingOrder.restore(local("buy").snapshot().toBuilder().status(OrderStatus.CANCEL_PENDING).build());
        var plan = policy.prepare(order, observed("buy", "live", "0", "USDT"));
        assertNull(plan.target());
        assertEquals("FILL_UNCONFIRMED", plan.executionStatus());
    }

    @Test
    void rejectsWrongIdentityTerminalDisagreementAndSeparateRebate() {
        var order = local("buy");
        assertThrows(IllegalStateException.class, () -> policy.prepare(TradingOrder.restore(order.snapshot().toBuilder().clientOrderId("other").build()), observed("buy", "filled", "0", "USDT")));
        var filled = TradingOrder.restore(order.snapshot().toBuilder().status(OrderStatus.FILLED).build());
        assertThrows(IllegalStateException.class, () -> policy.prepare(filled, observed("buy", "live", "0", "USDT")));
        assertThrows(IllegalStateException.class, () -> policy.prepare(order, new ExchangeOrderObservation(
                "exchange", "client", "BTC-USDT", "buy", "filled", "0.1", "1", "1", "100", "100", "0", "USDT")));
    }

    @Test
    void refusesToCloseFilledOrdersWithoutTrustworthyCumulativeAmounts() {
        var order = local("buy");
        for (String cumulative : new String[] {null, "", "0", "-1", "invalid"}) {
            var observation = new ExchangeOrderObservation("exchange", "client", "BTC-USDT", "buy", "filled",
                    "0", cumulative, "0", "100", "100", "0", "USDT");
            assertThrows(IllegalStateException.class, () -> policy.prepare(order, observation));
        }
        // The most recent trade is not a substitute for missing cumulative totals.
        assertThrows(IllegalStateException.class, () -> policy.prepare(order,
                new ExchangeOrderObservation("exchange", "client", "BTC-USDT", "buy", "filled",
                        "0", null, "0.2", "100", "100", "0", "USDT")));
        assertThrows(IllegalStateException.class, () -> policy.prepare(order,
                new ExchangeOrderObservation("exchange", "client", "BTC-USDT", "buy", "filled",
                        "0", "1", "0.2", null, "100", "0", "USDT")));
    }

    @Test
    void allowsUnfilledCancellationButRejectsLostPartialFillOnCancellation() {
        var canceled = new ExchangeOrderObservation("exchange", "client", "BTC-USDT", "buy", "canceled",
                "0", "0", "0", "", "", "0", "USDT");
        assertEquals(OrderStatus.CANCELED, policy.prepare(local("buy"), canceled).target());
        var partiallyFilled = TradingOrder.restore(local("buy").snapshot().toBuilder()
                .filledBaseAmount(new BigDecimal("0.5")).build());
        assertThrows(IllegalStateException.class, () -> policy.prepare(partiallyFilled, canceled));
    }

    @Test
    void rejectsMalformedFeeInsteadOfSilentlyTreatingItAsFree() {
        assertThrows(IllegalStateException.class,
                () -> policy.prepare(local("buy"), observed("buy", "filled", "invalid", "USDT")));
    }

    private static TradingOrder local(String side) {
        return TradingOrder.restore(TradingOrder.Snapshot.builder().instId("BTC-USDT").side(side).clientOrderId("client")
                .exchangeOrderId("exchange").status(OrderStatus.ACCEPTED).build());
    }

    private static ExchangeOrderObservation observed(String side, String state, String fee, String currency) {
        return new ExchangeOrderObservation("exchange", "client", "BTC-USDT", side, state,
                "0", "1", "0.1", "100", "101", fee, currency);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
