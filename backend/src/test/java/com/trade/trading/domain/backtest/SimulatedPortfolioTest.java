package com.trade.trading.domain.backtest;

import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SimulatedPortfolioTest {
    @Test
    void fillsAtOpenAndLiquidatesAtFinalCloseWithRealizedCost() {
        var portfolio = new SimulatedPortfolio(n("1000"), BigDecimal.ZERO, BigDecimal.ZERO);
        var candle = new SimulatedPortfolio.FillCandle("1700000000000", n("100"), n("120"));
        var buy = portfolio.execute(new StrategyDecision().setAction(TradingAction.BUY).setBuyQuoteAmount(n("500")), candle);
        assertEquals(FillPriceSource.OPEN, buy.fillPriceSource());
        assertDecimal("5", portfolio.getBase());
        assertDecimal("1100", portfolio.equity(n("120")));
        var sell = portfolio.closePosition("test", candle, "end");
        assertEquals(FillPriceSource.CLOSE, sell.fillPriceSource());
        assertDecimal("100", portfolio.getRealizedPnl());
        assertDecimal("0", portfolio.getPositionCost());
        assertDecimal("1100", portfolio.getCash());
    }

    @Test
    void feesAndSlippageDoNotAllowOverspending() {
        var portfolio = new SimulatedPortfolio(n("100"), n("0.01"), n("0.02"));
        portfolio.execute(new StrategyDecision().setAction(TradingAction.BUY).setBuyQuoteAmount(n("1000")),
                new SimulatedPortfolio.FillCandle("1700000000000", n("10"), n("10")));
        assertTrue(portfolio.getCash().signum() >= 0);
        assertTrue(portfolio.getBase().compareTo(n("10")) < 0);
        assertTrue(portfolio.getTotalFees().signum() > 0);
        assertDecimal("100", portfolio.getCash().add(portfolio.getPositionCost()));
    }

    @Test
    void drawdownUsesRunningPeak() {
        var points = BacktestStatistics.withDrawdowns(List.of(point("100"), point("120"), point("90"), point("110")));
        assertDecimal("0.25", BacktestStatistics.maxDrawdown(points));
        assertDecimal("0", points.get(1).drawdown());
    }

    private static BacktestEquityPoint point(String equity) {
        return new BacktestEquityPoint(Instant.EPOCH, n("1"), n(equity), n("0"), n(equity), n("0"));
    }
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static void assertDecimal(String expected, BigDecimal actual) { assertEquals(0, n(expected).compareTo(actual)); }
}
