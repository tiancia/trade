package com.trade.trading.domain.order;

import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class OrderSizingRulesTest {
    private final OrderSizingRules rules = new OrderSizingRules(new OrderSizingRules.Limits(
            new BigDecimal("100"), 2, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("50")));

    @Test
    void roundsAgainAfterExchangeCapAndRejectsBelowMinimum() {
        var facts = new OrderSizingRules.SizingFacts(BigDecimal.ZERO, new BigDecimal("10"), BigDecimal.ONE,
                new OrderSizingRules.InstrumentLimits(BigDecimal.ZERO, new BigDecimal("0.4"),
                        new BigDecimal("0.3"), new BigDecimal("0.5")));
        var decision = new StrategyDecision().setAction(TradingAction.SELL).setSellBaseAmount(new BigDecimal("2"));
        var result = rules.sellSize(decision, facts);
        assertFalse(result.isExecutable());
        assertTrue(result.getSkipReason().contains("below OKX minSz"));
    }

    @Test
    void buyHonorsEquityCapBeforeExchangeCapAndRoundsDown() {
        var facts = new OrderSizingRules.SizingFacts(new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ONE,
                new OrderSizingRules.InstrumentLimits(new BigDecimal("40.999"), BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ZERO));
        var result = rules.buySize(new StrategyDecision().setBuyQuoteAmount(new BigDecimal("200")), facts);
        assertTrue(result.isExecutable());
        assertEquals("40.99", result.getSize());
    }
}
