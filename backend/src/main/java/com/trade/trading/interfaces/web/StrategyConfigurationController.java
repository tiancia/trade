package com.trade.trading.interfaces.web;

import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.domain.model.StrategyConfigurationVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        return response(registry.configuration(strategyId));
    }

    @GetMapping("/history")
    public List<StrategyConfigurationVersion> history(@PathVariable String strategyId,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return registry.configurationHistory(strategyId, offset, limit).stream()
                .map(StrategyConfigurationController::response).toList();
    }

    @PutMapping
    public StrategyConfigurationVersion update(@PathVariable String strategyId, @RequestBody UpdateRequest request) {
        return response(registry.updateConfiguration(strategyId, request.expectedVersion(), request.reason(), request.params()));
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
