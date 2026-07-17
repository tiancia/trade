package com.trade.trading.execution;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BacktestBrokerTest {

    @Test
    void fillsSignalOnProvidedNextCandleOpenWithFeeAndSlippage() {
        BacktestBroker broker = new BacktestBroker(new BigDecimal("1000"), new BigDecimal("0.001"), new BigDecimal("0.01"));
        StrategyDecision decision = new StrategyDecision()
                .setStrategyId("backtest")
                .setAction(TradingAction.BUY)
                .setReason("signal")
                .setBuyQuoteAmount(new BigDecimal("100"));

        BacktestBroker.BacktestTrade trade = broker.execute(decision, candle("2026-05-17T00:01:00Z", "100"));

        assertEquals(0, new BigDecimal("101").compareTo(trade.price()));
        assertEquals(1, broker.trades().size());
        assertEquals(1, broker.getBase().signum());
    }

    @Test
    void allocatesRemainingCostWhenPositionIsSoldInParts() {
        BacktestBroker broker = new BacktestBroker(
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
        broker.execute(
                new StrategyDecision()
                        .setStrategyId("backtest")
                        .setAction(TradingAction.BUY)
                        .setBuyQuoteAmount(new BigDecimal("200")),
                candle("2026-05-17T00:01:00Z", "100")
        );

        BacktestBroker.BacktestTrade firstSell = broker.execute(
                new StrategyDecision()
                        .setStrategyId("backtest")
                        .setAction(TradingAction.SELL)
                        .setSellBaseAmount(new BigDecimal("0.5")),
                candle("2026-05-17T00:02:00Z", "120")
        );
        BacktestBroker.BacktestTrade finalSell = broker.execute(
                new StrategyDecision()
                        .setStrategyId("backtest")
                        .setAction(TradingAction.SELL),
                candle("2026-05-17T00:03:00Z", "90")
        );

        assertNotNull(firstSell);
        assertNotNull(finalSell);
        assertEquals(0, new BigDecimal("10").compareTo(firstSell.realizedPnl()));
        assertEquals(0, new BigDecimal("-15").compareTo(finalSell.realizedPnl()));
        assertEquals(0, new BigDecimal("-5").compareTo(broker.getRealizedPnl()));
        assertEquals(0, BigDecimal.ZERO.compareTo(broker.getBase()));
        assertEquals(0, BigDecimal.ZERO.compareTo(broker.getPositionCost()));
    }

    @Test
    void forcedCloseUsesFinalCandleCloseAndRecordsFeesInPnl() {
        BacktestBroker broker = new BacktestBroker(
                new BigDecimal("1000"),
                new BigDecimal("0.01"),
                BigDecimal.ZERO
        );
        broker.execute(
                new StrategyDecision()
                        .setStrategyId("backtest")
                        .setAction(TradingAction.BUY)
                        .setBuyQuoteAmount(new BigDecimal("100")),
                candle("2026-05-17T00:01:00Z", "100", "100")
        );

        BacktestBroker.BacktestTrade close = broker.closePosition(
                "backtest",
                candle("2026-05-17T00:02:00Z", "105", "110"),
                "end"
        );

        assertNotNull(close);
        assertEquals(0, new BigDecimal("110").compareTo(close.price()));
        assertEquals(BacktestBroker.FillPriceSource.CLOSE, close.fillPriceSource());
        assertEquals(0, BigDecimal.ZERO.compareTo(broker.getBase()));
        assertEquals(1, broker.getTotalFees().signum());
        assertEquals(1, close.realizedPnl().signum());
    }

    private static CandleResp candle(String ts, String open) {
        return candle(ts, open, open);
    }

    private static CandleResp candle(String ts, String open, String close) {
        CandleResp candle = new CandleResp();
        candle.setTs(String.valueOf(Instant.parse(ts).toEpochMilli()));
        candle.setOpen(open);
        candle.setClose(close);
        candle.setConfirm("1");
        return candle;
    }
}
