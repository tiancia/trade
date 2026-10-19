package com.trade.trading.interfaces.web;

import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.domain.model.StrategyConfigurationVersion;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.math.BigDecimal;

/** Operator parameter editing behind the same deployment gateway as strategy selection. */
@RestController
@RequestMapping("/api/trading/strategies/{strategyId}/configuration")
public class StrategyConfigurationController {
    private final TradingStrategyRegistry registry;

    public StrategyConfigurationController(TradingStrategyRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public StrategyConfigurationVersion configuration(@PathVariable String strategyId) {
        try {
            return response(registry.configuration(strategyId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping("/history")
    public List<StrategyConfigurationVersion> history(@PathVariable String strategyId,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        try {
            return registry.configurationHistory(strategyId, offset, limit).stream()
                    .map(StrategyConfigurationController::response).toList();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PutMapping
    public StrategyConfigurationVersion update(@PathVariable String strategyId, @RequestBody UpdateRequest request) {
        try {
            if (request == null) { throw new IllegalArgumentException("request body is required"); }
            return response(registry.updateConfiguration(strategyId, request.expectedVersion(), request.reason(), request.params()));
        } catch (ConcurrentModificationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    public record UpdateRequest(Long expectedVersion, String reason, Map<String, Object> params) {}

    /** Decimal text survives browser JSON parsing and an unrelated edit without rounding. */
    private static StrategyConfigurationVersion response(StrategyConfigurationVersion value) {
        Map<String, Object> params = new LinkedHashMap<>();
        value.params().forEach((key, parameter) -> params.put(key,
                parameter instanceof BigDecimal decimal ? decimal.toPlainString() : parameter));
        return new StrategyConfigurationVersion(value.strategyId(), value.type(), value.bar(), value.version(),
                value.changedAt(), value.reason(), params);
    }
}
