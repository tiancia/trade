package com.trade.common.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingMathTest {

    @Test
    void decimalReturnsZeroForBlankOrInvalidInput() {
        assertEquals(BigDecimal.ZERO, TradingMath.decimal(null));
        assertEquals(BigDecimal.ZERO, TradingMath.decimal(" "));
        assertEquals(BigDecimal.ZERO, TradingMath.decimal("not-a-number"));
    }

    @Test
    void decimalParsesTrimmedText() {
        assertEquals(new BigDecimal("12.340"), TradingMath.decimal(" 12.340 "));
    }

    @Test
    void percentChangeHandlesMissingBaseAndCalculatesRatio() {
        assertEquals(BigDecimal.ZERO, TradingMath.percentChange(new BigDecimal("10"), BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.2500000000"),
                TradingMath.percentChange(new BigDecimal("125"), new BigDecimal("100")));
    }

    @Test
    void clampHonorsPositiveUpperBoundOnly() {
        assertEquals(BigDecimal.ZERO, TradingMath.clamp(null, new BigDecimal("10")));
        assertEquals(new BigDecimal("5"), TradingMath.clamp(new BigDecimal("5"), new BigDecimal("10")));
        assertEquals(new BigDecimal("10"), TradingMath.clamp(new BigDecimal("15"), new BigDecimal("10")));
        assertEquals(new BigDecimal("15"), TradingMath.clamp(new BigDecimal("15"), BigDecimal.ZERO));
    }

    @Test
    void roundDownToStepKeepsValidTradingStep() {
        assertEquals(BigDecimal.ZERO, TradingMath.roundDownToStep(null, new BigDecimal("0.01")));
        assertEquals(new BigDecimal("1.230"),
                TradingMath.roundDownToStep(new BigDecimal("1.239"), new BigDecimal("0.01")));
        assertEquals(new BigDecimal("1.239"),
                TradingMath.roundDownToStep(new BigDecimal("1.239"), BigDecimal.ZERO));
    }

    @Test
    void plainReturnsNonScientificTextWithoutTrailingZeros() {
        assertEquals("0", TradingMath.plain(null));
        assertEquals("0", TradingMath.plain(new BigDecimal("0.000")));
        assertEquals("12.34", TradingMath.plain(new BigDecimal("12.3400")));
    }
}
