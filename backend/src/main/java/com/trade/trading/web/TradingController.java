package com.trade.trading.web;

import com.trade.trading.application.ActiveStrategySelection;
import com.trade.trading.application.TradingRuntimeStatus;
import com.trade.trading.application.TradingStrategySelectionService;
import com.trade.trading.application.TradingStrategyEngine;
import com.trade.trading.backtest.BacktestEquityPoint;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ConcurrentModificationException;
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
    private final TradingStrategySelectionService strategySelectionService;
    private final BacktestService backtestService;
    private final OrderLifecycleService orderLifecycleService;

    public TradingController(
            TradingStrategyRegistry strategyRegistry,
            TradingStrategyEngine tradingStrategyEngine,
            TradingStrategySelectionService strategySelectionService,
            BacktestService backtestService,
            OrderLifecycleService orderLifecycleService
    ) {
        this.strategyRegistry = strategyRegistry;
        this.tradingStrategyEngine = tradingStrategyEngine;
        this.strategySelectionService = strategySelectionService;
        this.backtestService = backtestService;
        this.orderLifecycleService = orderLifecycleService;
    }

    @GetMapping("/strategies")
    public List<TradingProperties.StrategyInstanceProperties> strategies() {
        return strategyRegistry.strategySummaries();
    }

    @PutMapping("/strategies/active")
    public ActiveStrategySelection activateStrategy(@RequestBody ActivateStrategyRequest request) {
        try {
            if (request == null) {
                throw new IllegalArgumentException("request body is required");
            }
            return strategySelectionService.activate(request.strategyId(), request.expectedRevision());
        } catch (ConcurrentModificationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
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
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BacktestRun startBacktest(@RequestBody BacktestRequest request) {
        try {
            return backtestService.start(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping("/backtests/{runId}")
    public BacktestRun backtest(@PathVariable String runId) {
        try {
            return backtestService.get(runId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @GetMapping("/backtests")
    public List<BacktestRun> backtests(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return backtestService.list(offset, limit);
    }

    @GetMapping("/backtests/{runId}/trades")
    public List<BacktestBroker.BacktestTrade> backtestTrades(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit
    ) {
        try {
            return backtestService.trades(runId, offset, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @GetMapping("/backtests/{runId}/equity")
    public List<BacktestEquityPoint> backtestEquity(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "500") int limit
    ) {
        try {
            return backtestService.equityCurve(runId, offset, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
}
