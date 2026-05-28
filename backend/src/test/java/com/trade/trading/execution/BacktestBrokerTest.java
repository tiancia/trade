package com.trade.trading.execution;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static CandleResp candle(String ts, String open) {
        CandleResp candle = new CandleResp();
        candle.setTs(String.valueOf(Instant.parse(ts).toEpochMilli()));
        candle.setOpen(open);
        candle.setConfirm("1");
        return candle;
    }
}
