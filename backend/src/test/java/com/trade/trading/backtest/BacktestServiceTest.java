package com.trade.trading.backtest;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.market.HistoricalCandleService;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.strategy.StrategyConfig;
import com.trade.trading.strategy.StrategyEvaluationContext;
import com.trade.trading.strategy.TradingStrategy;
import com.trade.trading.strategy.TradingStrategyRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BacktestServiceTest {

    @Test
    void replaysAtHistoricalTimesAndBuildsTradeAndEquityMetrics() {
        HistoricalCandleService history = mock(HistoricalCandleService.class);
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
        HistoricalCandleService history = mock(HistoricalCandleService.class);
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
        HistoricalCandleService history = mock(HistoricalCandleService.class);
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
        HistoricalCandleService history = mock(HistoricalCandleService.class);
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

    private static final class CapturingStrategy implements TradingStrategy<TestConfig> {
        private final List<Instant> evaluatedAt = new ArrayList<>();

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
