package com.trade.trading.domain.risk;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class AccountValuationTest {
    @Test
    void reportedEquityWinsAndFallbackMarksBaseAtCurrentPrice() {
        assertEquals(n("1000"), AccountValuation.equity(n("1000"), n("10"), n("2"), n("100")));
        assertEquals(n("210"), AccountValuation.equity(n("0"), n("10"), n("2"), n("100")));
        assertEquals(n("12"), AccountValuation.balance(n("0"), n("12"), n("20")));
        assertEquals(n("20"), AccountValuation.balance(n("0"), n("0"), n("20")));
    }

    @Test
    void disabledOrNonpositiveLimitDoesNotCapOrders() {
        assertEquals(BigDecimal.ZERO, AccountValuation.singleOpenLimit(false, n("0.1"), n("100")));
        assertEquals(BigDecimal.ZERO, AccountValuation.singleOpenLimit(true, null, n("100")));
        assertEquals(BigDecimal.ZERO, AccountValuation.singleOpenLimit(true, n("0.1"), n("0")));
        assertEquals(n("10.0"), AccountValuation.singleOpenLimit(true, n("0.1"), n("100")));
    }

    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
