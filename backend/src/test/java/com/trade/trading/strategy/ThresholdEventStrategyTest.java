package com.trade.trading.strategy;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThresholdEventStrategyTest {
    private final ThresholdEventStrategy strategy = new ThresholdEventStrategy();

    @Test
    void buysWhenPositivePriceMoveAndVolumeSpikeReachThresholds() {
        StrategyDecision decision = strategy.evaluate(context(candles("110", "100", "3000"), null), config());

        assertEquals(TradingAction.BUY, decision.getAction());
        assertEquals("threshold", decision.getStrategyId());
    }

    @Test
    void holdsWhenLatestCandleIsUnconfirmed() {
        List<CandleResp> candles = candles("110", "100", "3000");
        candles.getFirst().setConfirm("0");

        StrategyDecision decision = strategy.evaluate(context(candles, null), config());

        assertEquals(TradingAction.HOLD, decision.getAction());
    }

    @Test
    void sellsWhenTrackedPositionFloatingLossReachesThreshold() {
        TradingState state = new TradingState()
                .setTrackedBaseAmount(new BigDecimal("0.1"))
                .setAverageCost(new BigDecimal("100"));

        StrategyDecision decision = strategy.evaluate(context(candles("89", "100", "100"), state), config());

        assertEquals(TradingAction.SELL, decision.getAction());
    }

    @Test
    void holdsOnEmptyData() {
        StrategyDecision decision = strategy.evaluate(context(List.of(), null), config());

        assertEquals(TradingAction.HOLD, decision.getAction());
    }

    private static StrategyEvaluationContext context(List<CandleResp> candles, TradingState state) {
        TickerResp ticker = new TickerResp();
        ticker.setLast(candles.isEmpty() ? "0" : candles.getFirst().getClose());

        BalanceDetail base = new BalanceDetail();
        base.setCcy("BTC");
        base.setAvailBal(state == null ? "0" : "0.1");

        TradingDecisionContext decisionContext = new TradingDecisionContext()
                .setTicker(ticker)
                .setBaseBalance(base)
                .setOneMinuteCandles(candles)
                .setTradingState(state == null ? new TradingState() : state);

        return new StrategyEvaluationContext()
                .setStrategyId("threshold")
                .setBar("1m")
                .setProperties(new TradingProperties())
                .setMarketContext(decisionContext);
    }

    private static ThresholdEventStrategyConfig config() {
        return new ThresholdEventStrategyConfig()
                .setPriceMoveTriggerPercent(new BigDecimal("0.02"))
                .setVolumeSpikeMultiplier(new BigDecimal("3"))
                .setFloatingLossTriggerPercent(new BigDecimal("0.10"))
                .setBuyQuoteAmount(new BigDecimal("10"));
    }

    private static List<CandleResp> candles(String latestClose, String baseClose, String latestQuoteVolume) {
        List<CandleResp> candles = new ArrayList<>();
        candles.add(candle(latestClose, latestQuoteVolume));
        for (int i = 0; i < 3; i++) {
            candles.add(candle(baseClose, "100"));
        }
        candles.add(candle(baseClose, "100"));
        for (int i = 0; i < 20; i++) {
            candles.add(candle(baseClose, "100"));
        }
        return candles;
    }

    private static CandleResp candle(String close, String quoteVolume) {
        CandleResp candle = new CandleResp();
        candle.setTs("1");
        candle.setOpen(close);
        candle.setHigh(close);
        candle.setLow(close);
        candle.setClose(close);
        candle.setVolCcyQuote(quoteVolume);
        candle.setConfirm("1");
        return candle;
    }
}
