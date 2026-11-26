package com.trade.trading.application.strategy;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.application.order.OrderIdempotencyKeyFactory;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ThresholdEventStrategyTest {
    private static final Instant BAR_OPEN = Instant.parse("2026-05-17T12:00:00Z");
    private static final long MINUTE = 60_000L;
    private final ThresholdEventStrategy strategy = new ThresholdEventStrategy();

    @Test
    void buysWhenPositivePriceMoveAndVolumeSpikeReachThresholds() {
        StrategyDecision decision = strategy.evaluate(context(candles("110", "100", "3000"), null), config());

        assertEquals(TradingAction.BUY, decision.getAction());
        assertEquals("threshold", decision.getStrategyId());
    }

    @Test
    void confirmedFifteenMinuteSignalKeepsItsOrderIdentityAcrossQuotesUntilTheNextBar() {
        var rows = candles("110", "100", "3000");
        long bar = 15 * MINUTE;
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setTs(Long.toString(BAR_OPEN.toEpochMilli() - (i + 1L) * bar));
        }
        var ctx = context(List.of(), null).setBar("15m");
        var market = ctx.getMarketContext().setFifteenMinuteCandles(rows);
        var keys = new OrderIdempotencyKeyFactory();
        market.getTicker().setTs(Long.toString(BAR_OPEN.toEpochMilli()));
        var first = strategy.evaluate(ctx, config());
        assertEquals(TradingAction.BUY, first.getAction());
        String key = keys.create(ctx.getProperties(), first, market, new TradingDecisionRecord().setDecisionId("first"), "10");

        ctx.setEvaluatedAt(BAR_OPEN.plusSeconds(660));
        market.getTicker().setTs(Long.toString(ctx.getEvaluatedAt().toEpochMilli()));
        var replay = strategy.evaluate(ctx, config());
        assertEquals(TradingAction.BUY, replay.getAction());
        assertEquals(key, keys.create(ctx.getProperties(), replay, market, new TradingDecisionRecord().setDecisionId("next"), "8"));

        rows.forEach(row -> row.setTs(Long.toString(Long.parseLong(row.getTs()) + bar)));
        ctx.setEvaluatedAt(BAR_OPEN.plusMillis(bar));
        var next = strategy.evaluate(ctx, config());
        assertEquals(TradingAction.BUY, next.getAction());
        assertNotEquals(key, keys.create(ctx.getProperties(), next, market, new TradingDecisionRecord().setDecisionId("third"), "10"));
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "NaN", "0", "-1"})
    void invalidLatestCloseCannotTriggerAFalseLossExit(String close) {
        TradingState state = new TradingState()
                .setTrackedBaseAmount(new BigDecimal("0.1"))
                .setAverageCost(new BigDecimal("100"));

        StrategyDecision decision = strategy.evaluate(context(candles(close, "100", "100"), state), config());

        assertEquals(TradingAction.HOLD, decision.getAction());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "0", "-100"})
    void invalidComparisonCloseCannotTriggerAReverseMoveExit(String close) {
        TradingState state = new TradingState()
                .setTrackedBaseAmount(new BigDecimal("0.1"))
                .setAverageCost(new BigDecimal("100"));
        List<CandleResp> candles = candles("100", "100", "100");
        candles.get(4).setClose(close);

        StrategyDecision decision = strategy.evaluate(context(candles, state), config());

        assertEquals(TradingAction.HOLD, decision.getAction());
    }

    @Test
    void brokenPriceWindowCannotTriggerEntryButStillAllowsVerifiedLossExit() {
        List<CandleResp> candles = candles("110", "100", "3000");
        candles.get(2).setClose("invalid");

        assertEquals(TradingAction.HOLD, strategy.evaluate(context(candles, null), config()).getAction());

        candles.getFirst().setClose("89");
        TradingState state = new TradingState()
                .setTrackedBaseAmount(new BigDecimal("0.1"))
                .setAverageCost(new BigDecimal("100"));
        assertEquals(TradingAction.SELL, strategy.evaluate(context(candles, state), config()).getAction());
    }

    @Test
    void invalidUnconfirmedCloseDoesNotInvalidateConfirmedSignal() {
        List<CandleResp> candles = candles("110", "100", "3000");
        CandleResp unconfirmed = candle("invalid", "100");
        unconfirmed.setConfirm("0");
        unconfirmed.setTs(Long.toString(BAR_OPEN.toEpochMilli()));
        candles.addFirst(unconfirmed);

        assertEquals(TradingAction.BUY, strategy.evaluate(context(candles, null), config()).getAction());
    }

    @Test
    void holdsOnEmptyData() {
        StrategyDecision decision = strategy.evaluate(context(List.of(), null), config());

        assertEquals(TradingAction.HOLD, decision.getAction());
    }

    @ParameterizedTest
    @ValueSource(longs = {-60_000, 1, 60_000, 120_000})
    void rejectsStaleMisalignedOrNotYetClosedPricesForEntryAndExit(long shiftMillis) {
        var rows = candles("110", "100", "3000");
        rows.forEach(row -> row.setTs(Long.toString(Long.parseLong(row.getTs()) + shiftMillis)));
        assertEquals(TradingAction.HOLD, strategy.evaluate(context(rows, null), config()).getAction());

        rows.getFirst().setClose("80");
        var state = new TradingState().setTrackedBaseAmount(BigDecimal.ONE).setAverageCost(new BigDecimal("100"));
        assertEquals(TradingAction.HOLD, strategy.evaluate(context(rows, state), config()).getAction());
    }

    @Test
    void missingBarsInvalidateOnlyTheWindowsThatCrossTheGap() {
        var rows = candles("110", "100", "3000");
        rows.remove(12);
        assertEquals(TradingAction.HOLD, strategy.evaluate(context(rows, null), config()).getAction());

        var state = new TradingState().setTrackedBaseAmount(BigDecimal.ONE).setAverageCost(new BigDecimal("100"));
        rows.getFirst().setClose("97");
        assertEquals(TradingAction.SELL, strategy.evaluate(context(rows, state), config()).getAction());
        rows.remove(2);
        assertEquals(TradingAction.HOLD, strategy.evaluate(context(rows, state), config()).getAction());
        rows.getFirst().setClose("89");
        assertEquals(TradingAction.SELL, strategy.evaluate(context(rows, state), config()).getAction());
    }

    @Test
    void conflictingClosesForLatestTimestampCannotTriggerAStop() {
        var rows = candles("89", "100", "100");
        rows.get(1).setTs(rows.getFirst().getTs());
        var state = new TradingState().setTrackedBaseAmount(BigDecimal.ONE).setAverageCost(new BigDecimal("100"));
        assertEquals(TradingAction.HOLD, strategy.evaluate(context(rows, state), config()).getAction());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "-100"})
    void missingOrInvalidHistoricalVolumeCannotManufactureABuy(String invalidVolume) {
        var rows = candles("110", "100", "295");
        rows.get(10).setVolCcyQuote(invalidVolume);
        var decision = strategy.evaluate(context(rows, null), config());
        assertEquals(TradingAction.HOLD, decision.getAction());
        assertFalse(decision.getMetadata().containsKey("volumeSpikeRatio"));
    }

    @Test
    void volumeRatioUsesOneAvailableUnitForTheWholeWindowAndPreservesGenuineZero() {
        var rows = candles("110", "100", "3000");
        rows.forEach(row -> row.setVolCcy("100"));
        rows.getFirst().setVolCcy("200");
        rows.get(10).setVolCcyQuote(null);
        var decision = strategy.evaluate(context(rows, null), config());
        assertEquals(TradingAction.HOLD, decision.getAction());
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) decision.getMetadata().get("volumeSpikeRatio")));

        rows.getFirst().setVolCcy("300");
        assertEquals(TradingAction.BUY, strategy.evaluate(context(rows, null), config()).getAction());
        rows.get(10).setVolCcyQuote("100");
        rows.getFirst().setVolCcyQuote("0");
        decision = strategy.evaluate(context(rows, null), config());
        assertEquals(TradingAction.HOLD, decision.getAction());
        assertEquals(0, ((BigDecimal) decision.getMetadata().get("volumeSpikeRatio")).signum());
    }

    @Test
    void formingBarCanGenerateASignalWhenUnconfirmedCandlesAreEnabled() {
        var rows = candles("100", "100", "100");
        var forming = candle("110", "3000");
        forming.setTs(Long.toString(BAR_OPEN.toEpochMilli()));
        forming.setConfirm("0");
        rows.addFirst(forming);
        assertEquals(TradingAction.HOLD, strategy.evaluate(context(rows, null), config()).getAction());
        assertEquals(TradingAction.BUY, strategy.evaluate(context(rows, null),
                config().setRequireConfirmedCandle(false)).getAction());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 15})
    void readsOnlyTheConfiguredLookbackAtTheHistoricalBarClose(int minutes) {
        var rows = candles("110", "100", "3000");
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setTs(Long.toString(BAR_OPEN.toEpochMilli() - (i + 1L) * minutes * MINUTE));
        }
        List<CandleResp> history = new AbstractList<>() {
            @Override public CandleResp get(int index) {
                if (index > 21) throw new AssertionError("Read past the 21-bar signal window and its boundary");
                return rows.get(index);
            }
            @Override public int size() { return 50_000; }
        };
        var context = context(history, null).setBar(minutes + "m").setEvaluatedAt(BAR_OPEN);
        context.getMarketContext().setFiveMinuteCandles(history).setFifteenMinuteCandles(history);
        assertEquals(TradingAction.BUY, strategy.evaluate(context, config()).getAction());
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
                .setEvaluatedAt(BAR_OPEN.plusSeconds(30))
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
        for (int i = 0; i < candles.size(); i++) {
            candles.get(i).setTs(Long.toString(BAR_OPEN.toEpochMilli() - (i + 1L) * MINUTE));
        }
        return candles;
    }

    private static CandleResp candle(String close, String quoteVolume) {
        CandleResp candle = new CandleResp();
        candle.setOpen(close);
        candle.setHigh(close);
        candle.setLow(close);
        candle.setClose(close);
        candle.setVolCcyQuote(quoteVolume);
        candle.setConfirm("1");
        return candle;
    }
}
