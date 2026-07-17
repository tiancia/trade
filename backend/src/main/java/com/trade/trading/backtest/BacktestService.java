package com.trade.trading.backtest;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.common.support.TradingMath;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.execution.BacktestBroker;
import com.trade.trading.market.HistoricalCandleService;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingState;
import com.trade.trading.strategy.ConfiguredTradingStrategy;
import com.trade.trading.strategy.StrategyEvaluationContext;
import com.trade.trading.strategy.TradingStrategyRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class BacktestService {
    private static final int MAX_REQUEST_CANDLES = 50_000;
    private static final int MAX_PAGE_SIZE = 1_000;
    private static final int MAX_RETAINED_RUNS = 1_000;

    private final HistoricalCandleService historicalCandleService;
    private final TradingStrategyRegistry strategyRegistry;
    private final TradingProperties properties;
    private final Executor executor;
    private final boolean ownsExecutor;
    private final ConcurrentMap<String, BacktestRun> runs = new ConcurrentHashMap<>();

    @Autowired
    public BacktestService(
            HistoricalCandleService historicalCandleService,
            TradingStrategyRegistry strategyRegistry,
            TradingProperties properties
    ) {
        this(historicalCandleService, strategyRegistry, properties, newExecutor(), true);
    }

    BacktestService(
            HistoricalCandleService historicalCandleService,
            TradingStrategyRegistry strategyRegistry,
            TradingProperties properties,
            Executor executor
    ) {
        this(historicalCandleService, strategyRegistry, properties, executor, false);
    }

    private BacktestService(
            HistoricalCandleService historicalCandleService,
            TradingStrategyRegistry strategyRegistry,
            TradingProperties properties,
            Executor executor,
            boolean ownsExecutor
    ) {
        this.historicalCandleService = historicalCandleService;
        this.strategyRegistry = strategyRegistry;
        this.properties = properties;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
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

    public List<BacktestBroker.BacktestTrade> trades(String runId, int offset, int limit) {
        return page(get(runId).getTrades(), offset, limit);
    }

    public List<BacktestEquityPoint> equityCurve(String runId, int offset, int limit) {
        return page(get(runId).getEquityCurve(), offset, limit);
    }

    @PreDestroy
    public void shutdown() {
        if (!ownsExecutor || !(executor instanceof ExecutorService executorService)) {
            return;
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
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

            BacktestBroker broker = new BacktestBroker(
                    request.getInitialCash(),
                    request.getFeeRate(),
                    request.getSlippageRate()
            );
            List<BacktestBroker.BacktestTrade> trades = new ArrayList<>();
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
                addTrade(trades, broker.execute(decision, fillCandle), run.getRunId());
                equityCurve.add(equityPoint(broker, fillCandle));
                run.setProcessedCandleCount(i + 2);
            }

            CandleResp finalCandle = candles.getLast();
            if (request.isForceCloseAtEnd()) {
                addTrade(
                        trades,
                        broker.closePosition(configured.id(), finalCandle, "Forced close at end of backtest"),
                        run.getRunId()
                );
                equityCurve.set(equityCurve.size() - 1, equityPoint(broker, finalCandle));
            }

            List<BacktestEquityPoint> completedCurve = withDrawdowns(equityCurve);
            TradeStatistics statistics = statistics(trades);
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
                    .setMaxDrawdown(maxDrawdown(completedCurve))
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
            BacktestBroker broker,
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
        if (source == null) {
            throw new IllegalArgumentException("Backtest request is required");
        }
        String strategyId = trim(source.getStrategyId());
        if (strategyId == null) {
            throw new IllegalArgumentException("strategyId is required");
        }
        if (source.getFrom() == null || source.getTo() == null || !source.getFrom().isBefore(source.getTo())) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (properties.isDerivativeInstrument()) {
            throw new IllegalArgumentException(
                    "BacktestBroker currently supports SPOT only; derivative contract value and margin are not modeled"
            );
        }

        Map<String, Object> overrides = source.getParameterOverrides() == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source.getParameterOverrides()));
        ConfiguredTradingStrategy<?> configured = strategyRegistry.configuredStrategy(strategyId, overrides);
        BigDecimal initialCash = source.getInitialCash() == null
                ? new BigDecimal("1000")
                : source.getInitialCash();
        BigDecimal feeRate = source.getFeeRate() == null
                ? new BigDecimal("0.001")
                : source.getFeeRate();
        BigDecimal slippageRate = source.getSlippageRate() == null
                ? BigDecimal.ZERO
                : source.getSlippageRate();
        requirePositive(initialCash, "initialCash");
        requireRate(feeRate, "feeRate");
        requireRate(slippageRate, "slippageRate");
        if (source.getMaxCandles() < 2 || source.getMaxCandles() > MAX_REQUEST_CANDLES) {
            throw new IllegalArgumentException("maxCandles must be between 2 and " + MAX_REQUEST_CANDLES);
        }

        String instId = defaultIfBlank(source.getInstId(), properties.getInstId());
        String bar = defaultIfBlank(source.getBar(), configured.bar());
        if (trim(instId) == null) {
            throw new IllegalArgumentException("instId is required");
        }
        if (trim(bar) == null) {
            throw new IllegalArgumentException("bar is required");
        }

        BacktestRequest normalized = new BacktestRequest()
                .setStrategyId(strategyId)
                .setInstId(instId)
                .setBar(bar)
                .setFrom(source.getFrom())
                .setTo(source.getTo())
                .setInitialCash(initialCash)
                .setFeeRate(feeRate)
                .setSlippageRate(slippageRate)
                .setForceCloseAtEnd(source.isForceCloseAtEnd())
                .setIncludeUnconfirmed(source.isIncludeUnconfirmed())
                .setMaxCandles(source.getMaxCandles())
                .setParameterOverrides(overrides);
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

    private static BacktestEquityPoint equityPoint(BacktestBroker broker, CandleResp candle) {
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

    private static List<BacktestEquityPoint> withDrawdowns(List<BacktestEquityPoint> source) {
        List<BacktestEquityPoint> result = new ArrayList<>(source.size());
        BigDecimal high = BigDecimal.ZERO;
        for (BacktestEquityPoint point : source) {
            if (point.equity().compareTo(high) > 0) {
                high = point.equity();
            }
            BigDecimal drawdown = high.signum() == 0
                    ? BigDecimal.ZERO
                    : high.subtract(point.equity()).divide(high, 10, RoundingMode.HALF_UP);
            result.add(new BacktestEquityPoint(
                    point.candleTimestamp(),
                    point.markPrice(),
                    point.cash(),
                    point.baseAmount(),
                    point.equity(),
                    drawdown
            ));
        }
        return List.copyOf(result);
    }

    private static TradeStatistics statistics(List<BacktestBroker.BacktestTrade> trades) {
        int closedTrades = 0;
        int wins = 0;
        int losses = 0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        for (BacktestBroker.BacktestTrade trade : trades) {
            if (trade.action() != TradingAction.SELL) {
                continue;
            }
            closedTrades++;
            if (trade.realizedPnl().signum() > 0) {
                wins++;
                grossProfit = grossProfit.add(trade.realizedPnl());
            } else if (trade.realizedPnl().signum() < 0) {
                losses++;
                grossLoss = grossLoss.add(trade.realizedPnl().abs());
            }
        }
        BigDecimal winRate = closedTrades == 0
                ? BigDecimal.ZERO
                : new BigDecimal(wins).divide(new BigDecimal(closedTrades), 10, RoundingMode.HALF_UP);
        BigDecimal profitFactor = grossLoss.signum() == 0
                ? null
                : grossProfit.divide(grossLoss, 10, RoundingMode.HALF_UP);
        return new TradeStatistics(closedTrades, wins, losses, winRate, profitFactor);
    }

    private static BigDecimal maxDrawdown(List<BacktestEquityPoint> equityCurve) {
        return equityCurve.stream()
                .map(BacktestEquityPoint::drawdown)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private static void addTrade(
            List<BacktestBroker.BacktestTrade> trades,
            BacktestBroker.BacktestTrade trade,
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

    private static void requirePositive(BigDecimal value, String field) {
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireRate(BigDecimal value, String field) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(field + " must be greater than or equal to 0 and less than 1");
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        String normalized = trim(value);
        return normalized == null ? fallback : normalized;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().name("trading-backtest-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private record PreparedBacktest(
            BacktestRequest request,
            ConfiguredTradingStrategy<?> strategy
    ) {
    }

    private record TradeStatistics(
            int closedTrades,
            int wins,
            int losses,
            BigDecimal winRate,
            BigDecimal profitFactor
    ) {
    }
}
