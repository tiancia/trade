package com.trade.trading.interfaces.web;

import com.trade.trading.application.backtest.BacktestService;
import com.trade.trading.application.order.OrderLifecycleService;
import com.trade.trading.application.order.OrderReconciliationService;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.application.strategy.TradingStrategyEngine;
import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.application.strategy.TradingStrategySelectionService;
import com.trade.trading.domain.backtest.BacktestEquityPoint;
import com.trade.trading.domain.backtest.BacktestRequest;
import com.trade.trading.domain.backtest.BacktestRun;
import com.trade.trading.domain.backtest.BacktestTrade;
import com.trade.trading.domain.model.ActiveStrategySelection;
import com.trade.trading.domain.model.OrderReconciliationStatus;
import com.trade.trading.domain.model.TradingRuntimeStatus;
import com.trade.trading.domain.order.TradingOrder;
import com.trade.trading.domain.risk.FundSafetyState;
import com.trade.trading.infrastructure.config.TradingProperties;
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

import java.util.List;

/**
 * HTTP operator and backtest API for the OKX strategy domain.
 *
 * <p>Live decision execution remains scheduler-driven. Fund stop, resume, and
 * manual reconciliation are explicit operator mutations behind the deployment
 * gateway.</p>
 */
@RestController
@RequestMapping("/api/trading")
public class TradingController {
    private final TradingStrategyRegistry strategyRegistry;
    private final TradingStrategyEngine tradingStrategyEngine;
    private final TradingStrategySelectionService strategySelectionService;
    private final BacktestService backtestService;
    private final OrderLifecycleService orderLifecycleService;
    private final FundSafetyService fundSafetyService;
    private final OrderReconciliationService orderReconciliationService;
    private final TradingLeadershipService leadershipService;

    public TradingController(
            TradingStrategyRegistry strategyRegistry,
            TradingStrategyEngine tradingStrategyEngine,
            TradingStrategySelectionService strategySelectionService,
            BacktestService backtestService,
            OrderLifecycleService orderLifecycleService,
            FundSafetyService fundSafetyService,
            OrderReconciliationService orderReconciliationService,
            TradingLeadershipService leadershipService
    ) {
        this.strategyRegistry = strategyRegistry;
        this.tradingStrategyEngine = tradingStrategyEngine;
        this.strategySelectionService = strategySelectionService;
        this.backtestService = backtestService;
        this.orderLifecycleService = orderLifecycleService;
        this.fundSafetyService = fundSafetyService;
        this.orderReconciliationService = orderReconciliationService;
        this.leadershipService = leadershipService;
    }

    @GetMapping("/strategies")
    public List<TradingProperties.StrategyInstanceProperties> strategies() {
        return strategyRegistry.strategySummaries();
    }

    @PutMapping("/strategies/active")
    public ActiveStrategySelection activateStrategy(
            @RequestBody ActivateStrategyRequest request
    ) {
        return strategySelectionService.activate(request.strategyId(), request.expectedRevision());
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

    @GetMapping("/safety")
    public FundSafetyState fundSafety() {
        return fundSafetyService.state();
    }

    @PostMapping("/safety/stop")
    public FundSafetyState stopFunds(
            @RequestBody FundStopRequest request
    ) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fund stop reason is required");
        }
        return fundSafetyService.halt("operator-api", request.reason());
    }

    @PostMapping("/safety/resume")
    public FundSafetyState resumeFunds(
            @RequestBody FundResumeRequest request
    ) {
        try {
            return fundSafetyService.resume(
                    request.expectedVersion(),
                    request.reason(),
                    request.confirmation()
            );
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @PostMapping("/reconciliation/run")
    public OrderReconciliationStatus reconcileNow() {
        try {
            boolean executed = leadershipService.runIfLeader(
                    "manual-reconciliation",
                    orderReconciliationService::reconcileOnce
            );
            if (!executed) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Manual reconciliation must be sent to the current trading leader"
                );
            }
            return orderReconciliationService.status();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Trading reconciliation failed: " + e.getMessage(),
                    e
            );
        }
    }

    @PostMapping("/backtests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BacktestRun startBacktest(@RequestBody BacktestRequest request) {
        return backtestService.start(request);
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
    public List<BacktestTrade> backtestTrades(
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

    public record FundStopRequest(String reason) {
    }

    public record FundResumeRequest(long expectedVersion, String reason, String confirmation) {
    }
}
