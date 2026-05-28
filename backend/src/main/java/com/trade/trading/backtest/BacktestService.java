package com.trade.trading.backtest;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
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
import com.trade.trading.support.TradingMath;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class BacktestService {
    private final HistoricalCandleService historicalCandleService;
    private final TradingStrategyRegistry strategyRegistry;
    private final TradingProperties properties;
    private final ConcurrentMap<String, BacktestRun> runs = new ConcurrentHashMap<>();

    public BacktestService(
            HistoricalCandleService historicalCandleService,
            TradingStrategyRegistry strategyRegistry,
            TradingProperties properties
    ) {
        this.historicalCandleService = historicalCandleService;
        this.strategyRegistry = strategyRegistry;
        this.properties = properties;
    }

    public BacktestRun start(BacktestRequest request) {
        BacktestRequest normalized = normalize(request);
        BacktestRun run = new BacktestRun()
                .setRunId(UUID.randomUUID().toString())
                .setRequest(normalized);
        runs.put(run.getRunId(), run);
        CompletableFuture.runAsync(() -> execute(run));
        return run;
    }

    public BacktestRun get(String runId) {
        BacktestRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("Unknown backtest run: " + runId);
        }
        return run;
    }

    public List<BacktestBroker.BacktestTrade> trades(String runId, int offset, int limit) {
        BacktestRun run = get(runId);
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.max(limit, 1);
        if (safeOffset >= run.getTrades().size()) {
            return List.of();
        }
        int toIndex = Math.min(run.getTrades().size(), safeOffset + safeLimit);
        return run.getTrades().subList(safeOffset, toIndex);
    }

    private void execute(BacktestRun run) {
        run.setStatus(BacktestStatus.RUNNING)
                .setStartedAt(Instant.now());
        try {
            BacktestRequest request = run.getRequest();
            ConfiguredTradingStrategy<?> configured = strategyRegistry.configuredStrategy(
                    request.getStrategyId(),
                    request.getParameterOverrides()
            );
            List<CandleResp> candles = historicalCandleService.historyCandles(
                    request.getInstId(),
                    request.getBar(),
                    request.getFrom(),
                    request.getTo()
            );
            if (candles.size() < 2) {
                throw new IllegalArgumentException("Backtest requires at least two candles");
            }

            BacktestBroker broker = new BacktestBroker(
                    request.getInitialCash(),
                    request.getFeeRate(),
                    request.getSlippageRate()
            );
            List<BigDecimal> equityCurve = new ArrayList<>();
            BigDecimal grossProfit = BigDecimal.ZERO;
            BigDecimal grossLoss = BigDecimal.ZERO;
            BigDecimal openCost = BigDecimal.ZERO;
            int wins = 0;
            int closedTrades = 0;

            equityCurve.add(request.getInitialCash());
            for (int i = 0; i < candles.size() - 1; i++) {
                CandleResp signalCandle = candles.get(i);
                List<CandleResp> windowNewestFirst = new ArrayList<>(candles.subList(0, i + 1));
                Collections.reverse(windowNewestFirst);
                TradingDecisionContext decisionContext = context(request, broker, signalCandle, windowNewestFirst);
                StrategyDecision decision = evaluate(configured, request, decisionContext);
                BacktestBroker.BacktestTrade trade = broker.execute(decision, candles.get(i + 1));
                if (trade != null) {
                    BacktestBroker.BacktestTrade runTrade = new BacktestBroker.BacktestTrade(
                            run.getRunId(),
                            trade.strategyId(),
                            trade.action(),
                            trade.timestamp(),
                            trade.price(),
                            trade.baseAmount(),
                            trade.quoteAmount(),
                            trade.fee(),
                            trade.reason()
                    );
                    run.getTrades().add(runTrade);
                    if (runTrade.action() == TradingAction.BUY) {
                        openCost = openCost.add(runTrade.quoteAmount()).add(runTrade.fee());
                    } else if (runTrade.action() == TradingAction.SELL) {
                        closedTrades++;
                        BigDecimal proceeds = runTrade.quoteAmount().subtract(runTrade.fee());
                        BigDecimal pnl = proceeds.subtract(openCost);
                        openCost = BigDecimal.ZERO;
                        if (pnl.signum() >= 0) {
                            wins++;
                            grossProfit = grossProfit.add(pnl);
                        } else {
                            grossLoss = grossLoss.add(pnl.abs());
                        }
                    }
                }
                equityCurve.add(broker.equity(TradingMath.decimal(signalCandle.getClose())));
            }

            BigDecimal finalEquity = broker.equity(TradingMath.decimal(candles.getLast().getClose()));
            run.setFinalEquity(finalEquity)
                    .setTotalReturn(percentChange(finalEquity, request.getInitialCash()))
                    .setMaxDrawdown(maxDrawdown(equityCurve))
                    .setWinRate(closedTrades == 0 ? BigDecimal.ZERO
                            : new BigDecimal(wins).divide(new BigDecimal(closedTrades), 10, RoundingMode.HALF_UP))
                    .setProfitFactor(grossLoss.signum() == 0 ? BigDecimal.ZERO
                            : grossProfit.divide(grossLoss, 10, RoundingMode.HALF_UP))
                    .setTradeCount(run.getTrades().size())
                    .setStatus(BacktestStatus.SUCCEEDED)
                    .setCompletedAt(Instant.now());
        } catch (Exception e) {
            run.setStatus(BacktestStatus.FAILED)
                    .setError(e.getMessage())
                    .setCompletedAt(Instant.now());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private StrategyDecision evaluate(
            ConfiguredTradingStrategy configured,
            BacktestRequest request,
            TradingDecisionContext decisionContext
    ) {
        return configured.strategy().evaluate(new StrategyEvaluationContext()
                .setStrategyId(configured.id())
                .setBar(request.getBar())
                .setMarketContext(decisionContext)
                .setProperties(properties)
                .setEvaluatedAt(Instant.now()), configured.config());
    }

    private TradingDecisionContext context(
            BacktestRequest request,
            BacktestBroker broker,
            CandleResp signalCandle,
            List<CandleResp> windowNewestFirst
    ) {
        BigDecimal close = TradingMath.decimal(signalCandle.getClose());
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
                .setUpdatedAt(Instant.now().toString());

        return new TradingDecisionContext()
                .setTicker(ticker)
                .setAccountBalance(account)
                .setBaseBalance(base)
                .setQuoteBalance(quote)
                .setOneMinuteCandles(windowNewestFirst)
                .setFiveMinuteCandles(windowNewestFirst)
                .setTradingState(state);
    }

    private BacktestRequest normalize(BacktestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Backtest request is required");
        }
        if (request.getStrategyId() == null || request.getStrategyId().isBlank()) {
            throw new IllegalArgumentException("strategyId is required");
        }
        if (request.getFrom() == null || request.getTo() == null || !request.getFrom().isBefore(request.getTo())) {
            throw new IllegalArgumentException("from must be before to");
        }
        return request
                .setInstId(blankToDefault(request.getInstId(), properties.getInstId()))
                .setBar(blankToDefault(request.getBar(), "1m"))
                .setInitialCash(positiveOrDefault(request.getInitialCash(), new BigDecimal("1000")))
                .setFeeRate(zeroIfNull(request.getFeeRate()))
                .setSlippageRate(zeroIfNull(request.getSlippageRate()))
                .setParameterOverrides(request.getParameterOverrides() == null ? Map.of() : request.getParameterOverrides());
    }

    private static BigDecimal maxDrawdown(List<BigDecimal> equityCurve) {
        BigDecimal high = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (BigDecimal equity : equityCurve) {
            if (equity.compareTo(high) > 0) {
                high = equity;
            }
            if (high.signum() > 0) {
                BigDecimal drawdown = high.subtract(equity).divide(high, 10, RoundingMode.HALF_UP);
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }
        return maxDrawdown;
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal base) {
        return TradingMath.percentChange(current, base);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
