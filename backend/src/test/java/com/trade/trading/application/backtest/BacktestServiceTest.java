package com.trade.trading.application.backtest;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.application.strategy.StrategyConfig;
import com.trade.trading.application.strategy.IntradayTechnicalStrategy;
import com.trade.trading.application.strategy.IntradayTechnicalStrategyConfig;
import com.trade.trading.application.strategy.StrategyEvaluationContext;
import com.trade.trading.application.strategy.TradingStrategy;
import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.domain.backtest.BacktestRequest;
import com.trade.trading.domain.backtest.BacktestRun;
import com.trade.trading.domain.backtest.BacktestStatus;
import com.trade.trading.domain.backtest.FillPriceSource;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.port.HistoricalCandleSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BacktestServiceTest {

    @Test
    void realIntradayStrategyWarmsUpThenFillsTheNextOpenWithActualFeesAndSlippage() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        List<CandleResp> candles = breakoutHistory();
        when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt())).thenReturn(candles);
        CapturingIntradayStrategy strategy = new CapturingIntradayStrategy();
        TradingProperties properties = intradayProperties();
        // The request's execution costs must win over deployment estimates.
        properties.setTakerFeeRate(new BigDecimal("0.02"));
        BacktestService service = new BacktestService(history,
                new TradingStrategyRegistry(List.of(strategy), properties), properties, Runnable::run);
        BacktestRequest request = request().setBar("15m")
                .setTo(Instant.parse("2026-05-17T16:30:00Z"))
                .setFeeRate(new BigDecimal("0.001"))
                .setSlippageRate(new BigDecimal("0.0005"))
                .setForceCloseAtEnd(false);

        BacktestRun run = service.start(request);

        assertEquals(BacktestStatus.SUCCEEDED, run.getStatus(), run.getError());
        assertTrue(strategy.decisions.subList(0, 62).stream().allMatch(StrategyDecision::isHold));
        assertEquals(TradingAction.BUY, strategy.decisions.get(62).getAction(), strategy.decisions.get(62).getReason());
        assertEquals(TradingAction.SELL, strategy.decisions.get(64).getAction());
        assertEquals(Instant.parse("2026-05-17T15:45:00Z"), strategy.contexts.get(62).getEvaluatedAt());
        assertEquals(request.getFeeRate(), strategy.contexts.get(62).getEvaluationFeeRate());
        assertEquals(request.getSlippageRate(), strategy.contexts.get(62).getEvaluationSlippageRate());
        assertEquals(0.003, ((Number) strategy.decisions.get(62).getMetadata()
                .get("estimatedRoundTripCostPercent")).doubleValue(), 1e-12);
        assertEquals(2, run.getTradeCount());
        assertEquals(1, run.getClosedTradeCount());
        assertEquals(0, new BigDecimal("1.4545454545").compareTo(run.getClosedTradesPerDay()));
        assertTrue(strategy.contexts.stream().allMatch(context ->
                context.candlesNewestFirst().size() <= new IntradayTechnicalStrategyConfig().warmupCandles()));
        var buy = run.getTrades().getFirst();
        var sell = run.getTrades().getLast();
        assertEquals(FillPriceSource.OPEN, buy.fillPriceSource());
        assertEquals(FillPriceSource.OPEN, sell.fillPriceSource());
        assertEquals(Instant.ofEpochMilli(Long.parseLong(candles.get(63).getTs())), buy.timestamp());
        assertEquals(Instant.ofEpochMilli(Long.parseLong(candles.get(65).getTs())), sell.timestamp());
        assertEquals(0, new BigDecimal("107.0535").compareTo(buy.price()));
        assertEquals(0, new BigDecimal("111.7441").compareTo(sell.price()));
        assertTrue(buy.fee().signum() > 0);
        assertTrue(sell.fee().signum() > 0);
        assertTrue(run.getTotalFees().signum() > 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(run.getFinalBaseAmount()));
    }

    @Test
    void intradayReplayRejectsMissingBarsInsteadOfFillingAtANonAdjacentOpen() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        List<CandleResp> candles = breakoutHistory();
        candles.remove(63);
        when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt())).thenReturn(candles);
        var properties = intradayProperties();
        var service = new BacktestService(history,
                new TradingStrategyRegistry(List.of(new IntradayTechnicalStrategy()), properties), properties, Runnable::run);
        var run = service.start(request().setBar("15m").setTo(Instant.parse("2026-05-17T16:30:00Z")));
        assertEquals(BacktestStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("contains gaps"));
        assertEquals(0, run.getTradeCount());
    }

    @Test
    void intradayReplayRejectsOtherIntervalsAndUnconfirmedDataBeforeLoadingHistory() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        var strategy = new IntradayTechnicalStrategy();
        TradingProperties properties = intradayProperties();
        BacktestService service = new BacktestService(history,
                new TradingStrategyRegistry(List.of(strategy), properties), properties, Runnable::run);

        assertEquals("Intraday technical backtests require bar=15m", assertThrows(IllegalArgumentException.class,
                () -> service.start(request())).getMessage());
        assertEquals("Intraday technical backtests require includeUnconfirmed=false", assertThrows(IllegalArgumentException.class,
                () -> service.start(request().setBar("15m").setTo(Instant.parse("2026-05-17T00:30:00Z"))
                        .setIncludeUnconfirmed(true))).getMessage());
        verifyNoInteractions(history);
    }

    @Test
    void replayKeepsCandleIntervalsDistinctAndExposesOnlyPastFifteenMinuteCandles() {
        for (String bar : List.of("1m", "5m", "15m")) {
            HistoricalCandleSource history = mock(HistoricalCandleSource.class);
            long intervalMinutes = Long.parseLong(bar.substring(0, bar.length() - 1));
            Instant first = Instant.parse("2026-05-17T00:00:00Z");
            when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt()))
                    .thenReturn(List.of(
                            candle(first.toString(), "100", "100", "1"),
                            candle(first.plusSeconds(intervalMinutes * 60).toString(), "100", "101", "1"),
                            candle(first.plusSeconds(intervalMinutes * 120).toString(), "101", "102", "1")
                    ));
            CapturingStrategy strategy = new CapturingStrategy();
            TradingProperties properties = properties(strategy.type());
            properties.getStrategies().getFirst().setBar(bar);
            BacktestService service = new BacktestService(history,
                    new TradingStrategyRegistry(List.of(strategy), properties), properties, Runnable::run);

            BacktestRun run = service.start(request().setBar(bar).setTo(first.plusSeconds(intervalMinutes * 180)));

            assertEquals(BacktestStatus.SUCCEEDED, run.getStatus(), run.getError());
            assertEquals(2, strategy.contexts.size());
            for (int i = 0; i < strategy.contexts.size(); i++) {
                StrategyEvaluationContext context = strategy.contexts.get(i);
                var market = context.getMarketContext();
                assertEquals("1m".equals(bar) ? i + 1 : 0, market.getOneMinuteCandles().size());
                assertEquals("5m".equals(bar) ? i + 1 : 0, market.getFiveMinuteCandles().size());
                assertEquals("15m".equals(bar) ? i + 1 : 0, market.getFifteenMinuteCandles().size());
                assertEquals(i + 1, context.candlesNewestFirst().size());
                assertTrue(context.candlesNewestFirst().stream().allMatch(candle ->
                        Long.parseLong(candle.getTs()) <= context.getEvaluatedAt().toEpochMilli()));
                assertThrows(UnsupportedOperationException.class,
                        () -> context.candlesNewestFirst().clear());
            }
        }
    }

    @Test
    void rejectsTruncatedHistoricalCoverageAtEitherEndInsteadOfReportingAFullRange() {
        for (int missing : List.of(0, 2)) {
            HistoricalCandleSource history = mock(HistoricalCandleSource.class);
            List<CandleResp> candles = new ArrayList<>(List.of(
                    candle("2026-05-17T00:00:00Z", "100", "100", "1"),
                    candle("2026-05-17T00:01:00Z", "100", "100", "1"),
                    candle("2026-05-17T00:02:00Z", "100", "100", "1")));
            candles.remove(missing);
            when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt())).thenReturn(candles);
            CapturingStrategy strategy = new CapturingStrategy();
            TradingProperties properties = properties(strategy.type());
            BacktestService service = new BacktestService(history,
                    new TradingStrategyRegistry(List.of(strategy), properties), properties, Runnable::run);

            BacktestRun run = service.start(request());

            assertEquals(BacktestStatus.FAILED, run.getStatus());
            assertTrue(run.getError().contains("expected 3 usable candles but received 2"));
            assertTrue(strategy.contexts.isEmpty());
            assertEquals(0, run.getTradeCount());
        }
    }

    @Test
    void ignoresCandlesOutsideTheHalfOpenRequestRangeIncludingTheInclusiveSourceEnd() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt())).thenReturn(List.of(
                candle("2026-05-16T23:59:00Z", "100", "100", "1"),
                candle("2026-05-17T00:00:00Z", "100", "100", "1"),
                candle("2026-05-17T00:01:00Z", "100", "100", "1"),
                candle("2026-05-17T00:02:00Z", "100", "100", "1"),
                candle("2026-05-17T00:03:00Z", "100", "200", "1")));
        CapturingStrategy strategy = new CapturingStrategy();
        TradingProperties properties = properties(strategy.type());
        BacktestService service = new BacktestService(history,
                new TradingStrategyRegistry(List.of(strategy), properties), properties, Runnable::run);

        BacktestRun run = service.start(request());

        assertEquals(BacktestStatus.SUCCEEDED, run.getStatus(), run.getError());
        assertEquals(3, run.getCandleCount());
        assertEquals(Instant.parse("2026-05-17T00:02:00Z"), run.getLastCandleAt());
        assertEquals(0, BigDecimal.ZERO.compareTo(run.getBenchmarkReturn()));
    }

    @Test
    void onlyExplicitUnconfirmedRunsCanUseACandleClosingAfterTheRequestedEnd() {
        for (boolean includeUnconfirmed : List.of(false, true)) {
            HistoricalCandleSource history = mock(HistoricalCandleSource.class);
            when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt())).thenReturn(List.of(
                    candle("2026-05-17T00:00:00Z", "100", "100", "1"),
                    candle("2026-05-17T00:01:00Z", "100", "100", "1"),
                    candle("2026-05-17T00:02:00Z", "100", "100", "0")));
            CapturingStrategy strategy = new CapturingStrategy();
            TradingProperties properties = properties(strategy.type());
            BacktestService service = new BacktestService(history,
                    new TradingStrategyRegistry(List.of(strategy), properties), properties, Runnable::run);

            BacktestRun run = service.start(request().setTo(Instant.parse("2026-05-17T00:02:30Z"))
                    .setIncludeUnconfirmed(includeUnconfirmed));

            assertEquals(BacktestStatus.SUCCEEDED, run.getStatus(), run.getError());
            assertEquals(includeUnconfirmed ? 3 : 2, run.getCandleCount());
        }
    }

    @Test
    void rejectsMisalignedCandlesEvenWhenTheReturnedCountMatches() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt())).thenReturn(List.of(
                candle("2026-05-17T00:00:00Z", "100", "100", "1"),
                candle("2026-05-17T00:01:30Z", "100", "100", "1"),
                candle("2026-05-17T00:02:00Z", "100", "100", "1")));
        CapturingStrategy strategy = new CapturingStrategy();
        TradingProperties properties = properties(strategy.type());
        BacktestService service = new BacktestService(history,
                new TradingStrategyRegistry(List.of(strategy), properties), properties, Runnable::run);

        BacktestRun run = service.start(request());

        assertEquals(BacktestStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("misaligned 1m candles"));
        assertTrue(strategy.contexts.isEmpty());
    }

    @Test
    void rejectsUnsupportedBarsAndInsufficientCandleLimitsBeforeLoadingHistory() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        CapturingStrategy strategy = new CapturingStrategy();
        TradingProperties properties = properties(strategy.type());
        BacktestService service = new BacktestService(history,
                new TradingStrategyRegistry(List.of(strategy), properties), properties, Runnable::run);

        assertEquals("bar must be one of 1m, 5m or 15m", assertThrows(IllegalArgumentException.class,
                () -> service.start(request().setBar("1H"))).getMessage());
        assertEquals("Backtest range requires 3 candles, exceeding maxCandles=2",
                assertThrows(IllegalArgumentException.class,
                        () -> service.start(request().setMaxCandles(2))).getMessage());
        verifyNoInteractions(history);
    }

    @Test
    void boundsRetainedRunsAtomicallyAndEvictsOnlyCompletedRuns() throws Exception {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        CapturingStrategy strategy = new CapturingStrategy();
        TradingProperties properties = properties(strategy.type());
        List<Runnable> queued = Collections.synchronizedList(new ArrayList<>());
        BacktestService service = new BacktestService(history,
                new TradingStrategyRegistry(List.of(strategy), properties), properties, queued::add);
        try (var callers = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> submissions = IntStream.range(0, 1_100).mapToObj(index -> (Callable<Boolean>) () -> {
                try {
                    service.start(request());
                    return true;
                } catch (IllegalArgumentException error) {
                    assertEquals("Backtest capacity is full with active runs; retry later", error.getMessage());
                    return false;
                }
            }).toList();
            int accepted = 0;
            for (var result : callers.invokeAll(submissions)) {
                if (result.get()) accepted++;
            }
            assertEquals(1_000, accepted);
        }
        assertEquals(1_000, service.list(0, 1_000).size());
        assertEquals(1_000, queued.size());
        BacktestRun completed = service.list(0, 1).getFirst().setStatus(BacktestStatus.FAILED);

        BacktestRun replacement = service.start(request());

        assertEquals(BacktestStatus.QUEUED, replacement.getStatus());
        assertEquals(replacement, service.get(replacement.getRunId()));
        assertEquals(1_000, service.list(0, 1_000).size());
        assertThrows(IllegalArgumentException.class, () -> service.get(completed.getRunId()));
        verifyNoInteractions(history);
    }

    @Test
    void keepsRejectedExecutorRunsQueryableAsFailed() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        CapturingStrategy strategy = new CapturingStrategy();
        TradingProperties properties = properties(strategy.type());
        BacktestService service = new BacktestService(history,
                new TradingStrategyRegistry(List.of(strategy), properties), properties,
                command -> { throw new RejectedExecutionException(); });

        BacktestRun run = service.start(request());

        assertEquals(BacktestStatus.FAILED, service.get(run.getRunId()).getStatus());
        assertEquals("Backtest queue is full; retry later", run.getError());
        verifyNoInteractions(history);
    }

    @Test
    void replaysAtHistoricalTimesAndBuildsTradeAndEquityMetrics() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        List<CandleResp> candles = List.of(
                candle("2026-05-17T00:00:00Z", "100", "100", "1"),
                candle("2026-05-17T00:01:00Z", "100", "105", "1"),
                candle("2026-05-17T00:02:00Z", "110", "110", "1")
        );
        when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(candles);
        CapturingStrategy strategy = new CapturingStrategy();
        TradingProperties properties = properties(strategy.type());
        BacktestService service = new BacktestService(
                history,
                new TradingStrategyRegistry(List.of(strategy), properties),
                properties,
                Runnable::run
        );

        BacktestRun run = service.start(request());

        assertEquals(BacktestStatus.SUCCEEDED, run.getStatus());
        assertEquals(List.of(
                Instant.parse("2026-05-17T00:00:00Z"),
                Instant.parse("2026-05-17T00:01:00Z")
        ), strategy.evaluatedAt);
        assertEquals(3, run.getCandleCount());
        assertEquals(3, run.getProcessedCandleCount());
        assertEquals(2, run.getTradeCount());
        assertEquals(1, run.getClosedTradeCount());
        assertEquals(1, run.getWinningTradeCount());
        assertEquals(0, new BigDecimal("480").compareTo(run.getClosedTradesPerDay()));
        assertEquals(0, new BigDecimal("1010").compareTo(run.getFinalEquity()));
        assertEquals(0, new BigDecimal("0.0100000000").compareTo(run.getTotalReturn()));
        assertEquals(0, new BigDecimal("0.1000000000").compareTo(run.getBenchmarkReturn()));
        assertEquals(0, new BigDecimal("10").compareTo(run.getRealizedPnl()));
        assertEquals(0, BigDecimal.ZERO.compareTo(run.getUnrealizedPnl()));
        assertEquals(3, service.equityCurve(run.getRunId(), 0, 100).size());
        assertEquals(0, new BigDecimal("1005").compareTo(
                service.equityCurve(run.getRunId(), 1, 1).getFirst().equity()
        ));
        assertEquals(run.getRunId(), service.list(0, 10).getFirst().getRunId());
    }

    @Test
    void validatesRatesBeforeSchedulingOrLoadingHistory() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        CapturingStrategy strategy = new CapturingStrategy();
        TradingProperties properties = properties(strategy.type());
        BacktestService service = new BacktestService(
                history,
                new TradingStrategyRegistry(List.of(strategy), properties),
                properties,
                Runnable::run
        );
        BacktestRequest request = request().setFeeRate(new BigDecimal("-0.01"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.start(request));

        assertEquals("feeRate must be greater than or equal to 0 and less than 1", error.getMessage());
        verifyNoInteractions(history);
    }

    @Test
    void excludesUnconfirmedCandlesByDefault() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        candle("2026-05-17T00:00:00Z", "100", "100", "1"),
                        candle("2026-05-17T00:01:00Z", "100", "101", "0")
                ));
        CapturingStrategy strategy = new CapturingStrategy();
        TradingProperties properties = properties(strategy.type());
        BacktestService service = new BacktestService(
                history,
                new TradingStrategyRegistry(List.of(strategy), properties),
                properties,
                Runnable::run
        );

        BacktestRun run = service.start(request());

        assertEquals(BacktestStatus.FAILED, run.getStatus());
        assertEquals("Backtest requires at least two usable candles", run.getError());
    }

    @Test
    void marksOpenPositionToMarketWhenForcedCloseIsDisabled() {
        HistoricalCandleSource history = mock(HistoricalCandleSource.class);
        when(history.historyCandles(anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        candle("2026-05-17T00:00:00Z", "100", "100", "1"),
                        candle("2026-05-17T00:01:00Z", "100", "120", "1"),
                        candle("2026-05-17T00:02:00Z", "100", "90", "1")
                ));
        BuyAndHoldStrategy strategy = new BuyAndHoldStrategy();
        TradingProperties properties = properties(strategy.type());
        BacktestService service = new BacktestService(
                history,
                new TradingStrategyRegistry(List.of(strategy), properties),
                properties,
                Runnable::run
        );

        BacktestRun run = service.start(request().setForceCloseAtEnd(false));

        assertEquals(BacktestStatus.SUCCEEDED, run.getStatus());
        assertEquals(1, run.getTradeCount());
        assertEquals(0, run.getClosedTradeCount());
        assertEquals(0, new BigDecimal("1").compareTo(run.getFinalBaseAmount()));
        assertEquals(0, new BigDecimal("-10").compareTo(run.getUnrealizedPnl()));
        assertEquals(0, new BigDecimal("990").compareTo(run.getFinalEquity()));
        assertEquals(0, new BigDecimal("0.0294117647").compareTo(run.getMaxDrawdown()));
    }

    private static BacktestRequest request() {
        return new BacktestRequest()
                .setStrategyId("test-strategy")
                .setInstId("BTC-USDT")
                .setBar("1m")
                .setFrom(Instant.parse("2026-05-17T00:00:00Z"))
                .setTo(Instant.parse("2026-05-17T00:03:00Z"))
                .setInitialCash(new BigDecimal("1000"))
                .setFeeRate(BigDecimal.ZERO)
                .setSlippageRate(BigDecimal.ZERO);
    }

    private static TradingProperties intradayProperties() {
        TradingProperties properties = properties(IntradayTechnicalStrategy.TYPE);
        var instance = properties.getStrategies().getFirst();
        instance.setBar("15m");
        instance.setParams(Map.of(
                "mode", "VOLUME_BREAKOUT",
                "entry-rsi-max", 100,
                "take-profit-percent", new BigDecimal("0.03"),
                "buy-quote-amount", new BigDecimal("100")
        ));
        return properties;
    }

    private static List<CandleResp> breakoutHistory() {
        Instant from = Instant.parse("2026-05-17T00:00:00Z");
        List<CandleResp> candles = new ArrayList<>();
        for (int i = 0; i < 63; i++) {
            BigDecimal close = new BigDecimal("100").add(BigDecimal.valueOf(i, 1));
            candles.add(intradayCandle(from.plusSeconds(900L * i), close.subtract(new BigDecimal("0.05")),
                    close, close.add(new BigDecimal("0.05")), close.subtract(new BigDecimal("0.15"))));
        }
        candles.add(intradayCandle(from.plusSeconds(900L * 63), new BigDecimal("107"),
                new BigDecimal("108"), new BigDecimal("108.1"), new BigDecimal("106.9")));
        candles.add(intradayCandle(from.plusSeconds(900L * 64), new BigDecimal("108"),
                new BigDecimal("112"), new BigDecimal("112.1"), new BigDecimal("107.9")));
        candles.add(intradayCandle(from.plusSeconds(900L * 65), new BigDecimal("111.8"),
                new BigDecimal("111.9"), new BigDecimal("112"), new BigDecimal("111.7")));
        return candles;
    }

    private static CandleResp intradayCandle(Instant timestamp, BigDecimal open, BigDecimal close,
                                           BigDecimal high, BigDecimal low) {
        CandleResp candle = candle(timestamp.toString(), open.toPlainString(), close.toPlainString(), "1");
        candle.setHigh(high.toPlainString());
        candle.setLow(low.toPlainString());
        candle.setVol("100");
        return candle;
    }

    private static TradingProperties properties(String strategyType) {
        TradingProperties properties = new TradingProperties();
        TradingProperties.StrategyInstanceProperties instance = new TradingProperties.StrategyInstanceProperties();
        instance.setId("test-strategy");
        instance.setType(strategyType);
        instance.setEnabled(true);
        instance.setBar("1m");
        properties.setStrategies(List.of(instance));
        return properties;
    }

    private static CandleResp candle(String timestamp, String open, String close, String confirm) {
        CandleResp candle = new CandleResp();
        candle.setTs(String.valueOf(Instant.parse(timestamp).toEpochMilli()));
        candle.setOpen(open);
        candle.setHigh(close);
        candle.setLow(open);
        candle.setClose(close);
        candle.setVolCcyQuote("100");
        candle.setConfirm(confirm);
        return candle;
    }

    private static final class TestConfig implements StrategyConfig {
    }

    private static final class CapturingIntradayStrategy extends IntradayTechnicalStrategy {
        private final List<StrategyEvaluationContext> contexts = new ArrayList<>();
        private final List<StrategyDecision> decisions = new ArrayList<>();

        @Override
        public StrategyDecision evaluate(StrategyEvaluationContext context, IntradayTechnicalStrategyConfig config) {
            contexts.add(context);
            StrategyDecision decision = super.evaluate(context, config);
            decisions.add(decision);
            return decision;
        }
    }

    private static final class CapturingStrategy implements TradingStrategy<TestConfig> {
        private final List<Instant> evaluatedAt = new ArrayList<>();
        private final List<StrategyEvaluationContext> contexts = new ArrayList<>();

        @Override
        public String type() {
            return "test";
        }

        @Override
        public Class<TestConfig> configType() {
            return TestConfig.class;
        }

        @Override
        public StrategyDecision evaluate(StrategyEvaluationContext context, TestConfig config) {
            evaluatedAt.add(context.getEvaluatedAt());
            contexts.add(context);
            if (evaluatedAt.size() == 1) {
                return new StrategyDecision()
                        .setStrategyId(context.getStrategyId())
                        .setAction(TradingAction.BUY)
                        .setBuyQuoteAmount(new BigDecimal("100"))
                        .setReason("enter");
            }
            return new StrategyDecision()
                    .setStrategyId(context.getStrategyId())
                    .setAction(TradingAction.SELL)
                    .setSellBaseAmount(context.tradingState().getTrackedBaseAmount())
                    .setReason("exit");
        }
    }

    private static final class BuyAndHoldStrategy implements TradingStrategy<TestConfig> {
        private int evaluations;

        @Override
        public String type() {
            return "buy-and-hold";
        }

        @Override
        public Class<TestConfig> configType() {
            return TestConfig.class;
        }

        @Override
        public StrategyDecision evaluate(StrategyEvaluationContext context, TestConfig config) {
            evaluations++;
            if (evaluations == 1) {
                return new StrategyDecision()
                        .setStrategyId(context.getStrategyId())
                        .setAction(TradingAction.BUY)
                        .setBuyQuoteAmount(new BigDecimal("100"))
                        .setReason("enter");
            }
            return StrategyDecision.hold(context.getStrategyId(), "hold");
        }
    }
}
