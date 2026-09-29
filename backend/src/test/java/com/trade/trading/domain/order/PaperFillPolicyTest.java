package com.trade.trading.domain.order;

import com.trade.trading.domain.model.TradingAction;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class PaperFillPolicyTest {
    @Test
    void buyChargesBaseFeeAndSellChargesQuoteFee() {
        var fill = PaperFillPolicy.buy(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("0.01"));
        assertEquals(0, new BigDecimal("10").compareTo(fill.grossBase()));
        assertEquals(0, new BigDecimal("9.9").compareTo(fill.netBase()));
        assertEquals(0, new BigDecimal("-0.1").compareTo(fill.fee()));
        assertEquals(new BigDecimal("10.101010101010101010"), fill.averageCost());
        assertEquals(0, new BigDecimal("-1").compareTo(PaperFillPolicy.sellFee(
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("0.01"))));
    }

    @Test
    void instrumentAndShortPermissionsAreIndependentOfBroker() {
        assertNotNull(ExecutionEligibility.skipReason(TradingAction.BUY, false, true));
        assertNotNull(ExecutionEligibility.skipReason(TradingAction.OPEN_SHORT, false, false));
        assertNotNull(ExecutionEligibility.skipReason(TradingAction.OPEN_LONG, true, true));
        assertNull(ExecutionEligibility.skipReason(TradingAction.OPEN_LONG, false, false));
        assertNull(ExecutionEligibility.skipReason(TradingAction.SELL, true, false));
    }
}
