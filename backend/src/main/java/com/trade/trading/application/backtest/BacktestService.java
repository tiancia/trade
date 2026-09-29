package com.trade.trading.application.backtest;

import com.trade.trading.domain.backtest.BacktestPolicy;
import com.trade.trading.domain.backtest.BacktestStatistics;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.common.support.TradingMath;
import com.trade.trading.application.strategy.ConfiguredTradingStrategy;
import com.trade.trading.application.strategy.StrategyEvaluationContext;
import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.domain.backtest.BacktestEquityPoint;
import com.trade.trading.domain.backtest.BacktestRequest;
import com.trade.trading.domain.backtest.BacktestRun;
import com.trade.trading.domain.backtest.BacktestStatus;
import com.trade.trading.domain.backtest.BacktestTrade;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.backtest.SimulatedPortfolio;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.market.HistoricalCandleService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class BacktestService {
    private static final int MAX_PAGE_SIZE = 1_000;
    private static final int MAX_RETAINED_RUNS = 1_000;

    private final HistoricalCandleService historicalCandleService;
    private final TradingStrategyRegistry strategyRegistry;
    private final TradingProperties properties;
    private final Executor executor;
    private final ConcurrentMap<String, BacktestRun> runs = new ConcurrentHashMap<>();

    public BacktestService(
            HistoricalCandleService historicalCandleService,
            TradingStrategyRegistry strategyRegistry,
            TradingProperties properties,
            @Qualifier("backtestExecutor") Executor executor
    ) {
        this.historicalCandleService = historicalCandleService;
        this.strategyRegistry = strategyRegistry;
        this.properties = properties;
        this.executor = executor;
    }

    public BacktestRun start(BacktestRequest request) {
        PreparedBacktest prepared = prepare(request);
        BacktestRun run = new BacktestRun()
                .setRunId(UUID.randomUUID().toString())
                .setRequest(prepared.request());
        pruneRuns();
        runs.put(run.getRunId(), run);
        try {
            executor.execute(() -> execute(run, prepared.strategy()));
        } catch (RejectedExecutionException e) {
            fail(run, "Backtest queue is full; retry later");
        }
        return run;
    }

    public BacktestRun get(String runId) {
        BacktestRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("Unknown backtest run: " + runId);
        }
        return run;
    }

    public List<BacktestRun> list(int offset, int limit) {
        List<BacktestRun> ordered = runs.values().stream()
                .sorted(Comparator.comparing(BacktestRun::getCreatedAt).reversed())
                .toList();
        return page(ordered, offset, limit);
    }

    public List<BacktestTrade> trades(String runId, int offset, int limit) {
        return page(get(runId).getTrades(), offset, limit);
    }

    public List<BacktestEquityPoint> equityCurve(String runId, int offset, int limit) {
        return page(get(runId).getEquityCurve(), offset, limit);
    }


    private void execute(BacktestRun run, ConfiguredTradingStrategy<?> configured) {
        run.setStartedAt(Instant.now());
        run.setStatus(BacktestStatus.RUNNING);
        try {
            BacktestRequest request = run.getRequest();
            List<CandleResp> candles = usableCandles(
                    historicalCandleService.historyCandles(
                            request.getInstId(),
                            request.getBar(),
                            request.getFrom(),
                            request.getTo(),
                            request.getMaxCandles()
                    ),
                    request
            );
            run.setCandleCount(candles.size())
                    .setProcessedCandleCount(1)
                    .setFirstCandleAt(candleTime(candles.getFirst()))
                    .setLastCandleAt(candleTime(candles.getLast()));

            SimulatedPortfolio broker = new SimulatedPortfolio(
                    request.getInitialCash(),
                    request.getFeeRate(),
                    request.getSlippageRate()
            );
            List<BacktestTrade> trades = new ArrayList<>();
            List<BacktestEquityPoint> equityCurve = new ArrayList<>();
            CandleResp firstCandle = candles.getFirst();
            equityCurve.add(equityPoint(broker, firstCandle));

            for (int i = 0; i < candles.size() - 1; i++) {
                CandleResp signalCandle = candles.get(i);
                CandleResp fillCandle = candles.get(i + 1);
                List<CandleResp> windowNewestFirst = new ArrayList<>(candles.subList(0, i + 1));
                Collections.reverse(windowNewestFirst);
                Instant evaluatedAt = candleTime(signalCandle);
                TradingDecisionContext decisionContext = context(
                        broker,
                        signalCandle,
                        windowNewestFirst,
                        evaluatedAt
                );
                StrategyDecision decision = evaluate(configured, request, decisionContext, evaluatedAt);
                addTrade(trades, broker.execute(decision, fillCandle(fillCandle)), run.getRunId());
                equityCurve.add(equityPoint(broker, fillCandle));
                run.setProcessedCandleCount(i + 2);
            }

            CandleResp finalCandle = candles.getLast();
            if (request.isForceCloseAtEnd()) {
                addTrade(
                        trades,
                        broker.closePosition(configured.id(), fillCandle(finalCandle), "Forced close at end of backtest"),
                        run.getRunId()
                );
                equityCurve.set(equityCurve.size() - 1, equityPoint(broker, finalCandle));
            }

            List<BacktestEquityPoint> completedCurve = BacktestStatistics.withDrawdowns(equityCurve);
            var statistics = BacktestStatistics.statistics(trades);
            BigDecimal finalMark = price(finalCandle.getClose(), "close", finalCandle);
            BigDecimal finalEquity = broker.equity(finalMark);

            // Publish all result fields before the terminal status. The volatile
            // status write makes the completed snapshot visible to API readers.
            run.setTrades(List.copyOf(trades))
                    .setEquityCurve(completedCurve)
                    .setTradeCount(trades.size())
                    .setClosedTradeCount(statistics.closedTrades())
                    .setWinningTradeCount(statistics.wins())
                    .setLosingTradeCount(statistics.losses())
                    .setFinalEquity(finalEquity)
                    .setFinalCash(broker.getCash())
                    .setFinalBaseAmount(broker.getBase())
                    .setTotalReturn(TradingMath.percentChange(finalEquity, request.getInitialCash()))
                    .setBenchmarkReturn(TradingMath.percentChange(
                            finalMark,
                            price(firstCandle.getClose(), "close", firstCandle)
                    ))
                    .setMaxDrawdown(BacktestStatistics.maxDrawdown(completedCurve))
                    .setWinRate(statistics.winRate())
                    .setProfitFactor(statistics.profitFactor())
                    .setTotalFees(broker.getTotalFees())
                    .setRealizedPnl(broker.getRealizedPnl())
                    .setUnrealizedPnl(broker.unrealizedPnl(finalMark))
                    .setCompletedAt(Instant.now())
                    .setStatus(BacktestStatus.SUCCEEDED);
        } catch (Exception e) {
            fail(run, message(e));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private StrategyDecision evaluate(
            ConfiguredTradingStrategy configured,
            BacktestRequest request,
            TradingDecisionContext decisionContext,
            Instant evaluatedAt
    ) {
        return configured.strategy().evaluate(new StrategyEvaluationContext()
                .setStrategyId(configured.id())
                .setBar(request.getBar())
                .setMarketContext(decisionContext)
                .setProperties(properties)
                .setEvaluatedAt(evaluatedAt), configured.config());
    }

    private TradingDecisionContext context(
            SimulatedPortfolio broker,
            CandleResp signalCandle,
            List<CandleResp> windowNewestFirst,
            Instant evaluatedAt
    ) {
        BigDecimal close = price(signalCandle.getClose(), "close", signalCandle);
        TickerResp ticker = new TickerResp();
        ticker.setLast(TradingMath.plain(close));

        BalanceDetail base = new BalanceDetail();
        base.setCcy(properties.getBaseCcy());
        base.setAvailBal(TradingMath.plain(broker.getBase()));

        BalanceDetail quote = new BalanceDetail();
        quote.setCcy(properties.getQuoteCcy());
        quote.setAvailBal(TradingMath.plain(broker.getCash()));

        AccountBalanceResp account = new AccountBalanceResp();
        account.setTotalEq(TradingMath.plain(broker.equity(close)));

        TradingState state = new TradingState()
                .setTrackedBaseAmount(broker.getBase())
                .setAverageCost(broker.getAverageCost())
                .setUpdatedAt(evaluatedAt.toString());

        return new TradingDecisionContext()
                .setTicker(ticker)
                .setAccountBalance(account)
                .setBaseBalance(base)
                .setQuoteBalance(quote)
                .setOneMinuteCandles(windowNewestFirst)
                .setFiveMinuteCandles(windowNewestFirst)
                .setTradingState(state);
    }

    private PreparedBacktest prepare(BacktestRequest source) {
        String strategyId = BacktestPolicy.requireSelection(source, properties.isDerivativeInstrument());
        Map<String, Object> overrides = source.getParameterOverrides() == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source.getParameterOverrides()));
        ConfiguredTradingStrategy<?> configured = strategyRegistry.configuredStrategy(strategyId, overrides);
        BacktestRequest normalized = BacktestPolicy.normalize(source, strategyId, properties.getInstId(), configured.bar(), overrides);
        return new PreparedBacktest(normalized, configured);
    }

    private static List<CandleResp> usableCandles(List<CandleResp> source, BacktestRequest request) {
        if (source == null) {
            source = List.of();
        }
        Map<Long, CandleResp> unique = new LinkedHashMap<>();
        source.stream()
                .filter(candle -> candle != null && (request.isIncludeUnconfirmed() || "1".equals(candle.getConfirm())))
                .sorted(Comparator.comparingLong(BacktestService::timestamp))
                .forEach(candle -> unique.put(timestamp(candle), candle));
        List<CandleResp> candles = List.copyOf(unique.values());
        if (candles.size() < 2) {
            throw new IllegalArgumentException("Backtest requires at least two usable candles");
        }
        if (candles.size() > request.getMaxCandles()) {
            throw new IllegalArgumentException(
                    "Backtest returned more candles than maxCandles=" + request.getMaxCandles()
            );
        }
        for (CandleResp candle : candles) {
            candleTime(candle);
            price(candle.getOpen(), "open", candle);
            price(candle.getClose(), "close", candle);
        }
        return candles;
    }

    private static SimulatedPortfolio.FillCandle fillCandle(CandleResp candle) {
        return new SimulatedPortfolio.FillCandle(candle.getTs(),
                TradingMath.decimal(candle.getOpen()), TradingMath.decimal(candle.getClose()));
    }

    private static BacktestEquityPoint equityPoint(SimulatedPortfolio broker, CandleResp candle) {
        BigDecimal mark = price(candle.getClose(), "close", candle);
        return new BacktestEquityPoint(
                candleTime(candle),
                mark,
                broker.getCash(),
                broker.getBase(),
                broker.equity(mark),
                BigDecimal.ZERO
        );
    }

    private static void addTrade(
            List<BacktestTrade> trades,
            BacktestTrade trade,
            String runId
    ) {
        if (trade != null) {
            trades.add(trade.withRunId(runId));
        }
    }

    private static <T> List<T> page(List<T> source, int offset, int limit) {
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        if (safeOffset >= source.size()) {
            return List.of();
        }
        int toIndex = Math.min(source.size(), safeOffset + safeLimit);
        return List.copyOf(source.subList(safeOffset, toIndex));
    }

    private static Instant candleTime(CandleResp candle) {
        long timestamp = timestamp(candle);
        if (timestamp <= 0) {
            throw new IllegalArgumentException("Candle timestamp must be a positive epoch millisecond value");
        }
        return Instant.ofEpochMilli(timestamp);
    }

    private static long timestamp(CandleResp candle) {
        if (candle == null || candle.getTs() == null || candle.getTs().isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(candle.getTs());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static BigDecimal price(String value, String field, CandleResp candle) {
        BigDecimal price = TradingMath.decimal(value);
        if (price.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Candle " + field + " must be positive at ts=" + (candle == null ? null : candle.getTs())
            );
        }
        return price;
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private void fail(BacktestRun run, String error) {
        run.setError(error)
                .setCompletedAt(Instant.now())
                .setStatus(BacktestStatus.FAILED);
    }

    private void pruneRuns() {
        int removeCount = runs.size() - MAX_RETAINED_RUNS + 1;
        if (removeCount <= 0) {
            return;
        }
        runs.values().stream()
                .filter(run -> run.getStatus() == BacktestStatus.SUCCEEDED
                        || run.getStatus() == BacktestStatus.FAILED)
                .sorted(Comparator.comparing(BacktestRun::getCreatedAt))
                .limit(removeCount)
                .forEach(run -> runs.remove(run.getRunId(), run));
    }


    private record PreparedBacktest(
            BacktestRequest request,
            ConfiguredTradingStrategy<?> strategy
    ) {
    }

}
