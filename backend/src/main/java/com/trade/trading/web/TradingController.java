package com.trade.trading.web;

import com.trade.trading.application.TradingRuntimeStatus;
import com.trade.trading.application.TradingStrategyEngine;
import com.trade.trading.backtest.BacktestRequest;
import com.trade.trading.backtest.BacktestRun;
import com.trade.trading.backtest.BacktestService;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.execution.BacktestBroker;
import com.trade.trading.order.OrderLifecycleService;
import com.trade.trading.order.TradingOrder;
import com.trade.trading.strategy.TradingStrategyRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * HTTP read and backtest API for the OKX strategy domain.
 *
 * <p>Live decision execution remains scheduler-driven; this controller exposes
 * strategy configuration, runtime status, and backtest runs for operators and
 * frontends.</p>
 */
@RestController
@RequestMapping("/api/trading")
public class TradingController {
    private final TradingStrategyRegistry strategyRegistry;
    private final TradingStrategyEngine tradingStrategyEngine;
    private final BacktestService backtestService;
    private final OrderLifecycleService orderLifecycleService;

    public TradingController(
            TradingStrategyRegistry strategyRegistry,
            TradingStrategyEngine tradingStrategyEngine,
            BacktestService backtestService,
            OrderLifecycleService orderLifecycleService
    ) {
        this.strategyRegistry = strategyRegistry;
        this.tradingStrategyEngine = tradingStrategyEngine;
        this.backtestService = backtestService;
        this.orderLifecycleService = orderLifecycleService;
    }

    @GetMapping("/strategies")
    public List<TradingProperties.StrategyInstanceProperties> strategies() {
        return strategyRegistry.strategySummaries();
    }

    @GetMapping("/runtime/status")
    public TradingRuntimeStatus runtimeStatus() {
        return tradingStrategyEngine.status();
    }

    @GetMapping("/orders/{idempotencyKey}")
    public TradingOrder order(@PathVariable String idempotencyKey) {
        return orderLifecycleService.find(idempotencyKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    @PostMapping("/backtests")
    public BacktestRun startBacktest(@RequestBody BacktestRequest request) {
        return backtestService.start(request);
    }

    @GetMapping("/backtests/{runId}")
    public BacktestRun backtest(@PathVariable String runId) {
        return backtestService.get(runId);
    }

    @GetMapping("/backtests/{runId}/trades")
    public List<BacktestBroker.BacktestTrade> backtestTrades(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return backtestService.trades(runId, offset, limit);
    }
}
