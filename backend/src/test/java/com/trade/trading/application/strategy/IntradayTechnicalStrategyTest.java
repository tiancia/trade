package com.trade.trading.application.strategy;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntradayTechnicalStrategyTest {
    private static final long BAR = 900_000L;
    private static final long START = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
    private final IntradayTechnicalStrategy strategy = new IntradayTechnicalStrategy();

    @Test void indicatorsIgnoreFormingCandleAndDoNotFallBackToOtherBars() {
        StrategyEvaluationContext context = context();
        var baseline = strategy.evaluate(context, new IntradayTechnicalStrategyConfig());
        assertEquals(TradingAction.HOLD, baseline.getAction());
        assertEquals(50.0, baseline.getMetadata().get("rsi"));
        var extra = new ArrayList<>(context.getMarketContext().getFifteenMinuteCandles());
        extra.addFirst(candle(80, "999999", "0"));
        context.getMarketContext().setFifteenMinuteCandles(extra).setOneMinuteCandles(List.of(candle(80, "500000", "1")));
        assertEquals(baseline.getMetadata(), strategy.evaluate(context, new IntradayTechnicalStrategyConfig()).getMetadata());
        context.setBar("1H");
        assertTrue(context.candlesNewestFirst().isEmpty());
        assertTrue(strategy.evaluate(context, new IntradayTechnicalStrategyConfig()).isHold());
        context.setBar("15m").getMarketContext().setFifteenMinuteCandles(List.of());
        assertTrue(strategy.evaluate(context, new IntradayTechnicalStrategyConfig()).getReason().contains("warmup"));
    }

    @Test void stopUsesConfirmedCloseAndSellsOnlyManagedQuantity() {
        var context = context();
        context.getMarketContext().getFifteenMinuteCandles().set(0, candle(79, "98", "1"));
        context.getMarketContext().setTradingState(new TradingState().setAverageCost(new BigDecimal("100"))
                .setTrackedBaseAmount(new BigDecimal("0.25")));
        var decision = strategy.evaluate(context, new IntradayTechnicalStrategyConfig());
        assertEquals(TradingAction.SELL, decision.getAction());
        assertEquals(new BigDecimal("0.25"), decision.getSellBaseAmount());
        assertEquals("15m", decision.getMetadata().get("signalBar"));
        assertEquals(START + 79 * BAR, decision.getMetadata().get("signalCandleTs"));
    }

    @Test void rejectsMissingWarmupGapsBadOhlcFutureAndStaleCandles() {
        var config = new IntradayTechnicalStrategyConfig();
        var shortHistory = context();
        shortHistory.getMarketContext().setFifteenMinuteCandles(shortHistory.getMarketContext().getFifteenMinuteCandles().subList(0, 10));
        assertTrue(strategy.evaluate(shortHistory, config).getReason().contains("warmup"));
        var gap = context();
        gap.getMarketContext().getFifteenMinuteCandles().remove(5);
        assertTrue(strategy.evaluate(gap, config).getReason().contains("contiguous"));
        var malformed = context();
        malformed.getMarketContext().getFifteenMinuteCandles().getFirst().setHigh("1");
        assertTrue(strategy.evaluate(malformed, config).getReason().contains("Invalid"));
        assertTrue(strategy.evaluate(context().setEvaluatedAt(Instant.ofEpochMilli(START + 79 * BAR)), config).getReason().contains("not closed"));
        assertTrue(strategy.evaluate(context().setEvaluatedAt(Instant.ofEpochMilli(START + 81 * BAR)), config).getReason().contains("stale"));
        var derivative = context();
        derivative.getProperties().setInstType("SWAP");
        assertTrue(strategy.evaluate(derivative, config).getReason().contains("SPOT"));
    }

    @Test void deploymentAndBacktestOverridesShareStrictValidationWithoutMutatingBaseline() {
        TradingProperties properties = new TradingProperties();
        var instance = new TradingProperties.StrategyInstanceProperties();
        instance.setId("day");
        instance.setType(IntradayTechnicalStrategy.TYPE);
        instance.setEnabled(true);
        instance.setBar("15m");
        instance.setParams(Map.of());
        properties.setStrategies(List.of(instance));
        TradingStrategyRegistry registry = new TradingStrategyRegistry(List.of(strategy), properties);
        var baseline = registry.configuration("day");
        assertEquals(23, baseline.params().size());
        assertEquals("TREND_PULLBACK", baseline.params().get("mode"));
        var candidate = registry.configuredStrategy("day", Map.of("entry-rsi-max", "72", "estimated-slippage-rate", "0"));
        assertEquals(1, candidate.configuration().version());
        assertEquals(new BigDecimal("68"), registry.configuration("day").params().get("entry-rsi-max"));
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("rsi-period", 2.5),
                Map.of("ema-slow-period", 100), Map.of("rsi-period", "14"), Map.of("ema-fast-period", 25),
                Map.of("entry-rsi-min", 80), Map.of("mode", "UNKNOWN"), Map.of("stop-loss-percent", ".02"),
                Map.of("require-confirmed-candle", false), Map.of("estimated-slippage-rate", "NaN"))) {
            assertThrows(IllegalArgumentException.class, () -> registry.configuredStrategy("day", invalid), invalid.toString());
        }
    }

    private static StrategyEvaluationContext context() {
        List<CandleResp> candles = new ArrayList<>();
        for (int i = 79; i >= 0; i--) candles.add(candle(i, "100", "1"));
        return new StrategyEvaluationContext().setStrategyId("day").setBar("15m").setProperties(new TradingProperties())
                .setEvaluatedAt(Instant.ofEpochMilli(START + 80 * BAR))
                .setMarketContext(new TradingDecisionContext().setFifteenMinuteCandles(candles).setTradingState(new TradingState()));
    }
    private static CandleResp candle(int i, String close, String confirm) {
        BigDecimal value = new BigDecimal(close);
        return new CandleResp(List.of(String.valueOf(START + i * BAR), close, value.add(BigDecimal.ONE).toPlainString(),
                value.subtract(BigDecimal.ONE).toPlainString(), close, "100", "100", "10000", confirm));
    }
}
