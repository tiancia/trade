package com.trade.trading.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.trade.trading.config.TradingProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TradingStrategyRegistry {
    private final Map<String, TradingStrategy<? extends StrategyConfig>> strategies;
    private final TradingProperties properties;
    private final ObjectMapper objectMapper;

    public TradingStrategyRegistry(
            List<TradingStrategy<? extends StrategyConfig>> strategies,
            TradingProperties properties
    ) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(
                        strategy -> normalize(strategy.type()),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
    }

    public List<ConfiguredTradingStrategy<?>> configuredStrategies() {
        List<TradingProperties.StrategyInstanceProperties> configured = properties.getStrategies();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }

        List<ConfiguredTradingStrategy<?>> result = new ArrayList<>();
        for (TradingProperties.StrategyInstanceProperties item : configured) {
            if (item == null || !item.isEnabled()) {
                continue;
            }
            TradingStrategy<? extends StrategyConfig> strategy = strategies.get(normalize(item.getType()));
            if (strategy == null) {
                throw new IllegalArgumentException("Unknown trading strategy type: " + item.getType());
            }
            result.add(configure(item, strategy));
        }
        return result;
    }

    public List<TradingProperties.StrategyInstanceProperties> strategySummaries() {
        List<TradingProperties.StrategyInstanceProperties> configured = properties.getStrategies();
        return configured == null ? List.of() : configured;
    }

    public ConfiguredTradingStrategy<?> configuredStrategy(String strategyId) {
        return configuredStrategies().stream()
                .filter(strategy -> strategy.id().equals(strategyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown or disabled strategy id: " + strategyId));
    }

    public ConfiguredTradingStrategy<?> configuredStrategy(String strategyId, Map<String, Object> parameterOverrides) {
        ConfiguredTradingStrategy<?> configured = configuredStrategy(strategyId);
        if (parameterOverrides == null || parameterOverrides.isEmpty()) {
            return configured;
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        if (configured.source().getParams() != null) {
            merged.putAll(configured.source().getParams());
        }
        merged.putAll(parameterOverrides);
        TradingProperties.StrategyInstanceProperties source = new TradingProperties.StrategyInstanceProperties();
        source.setId(configured.id());
        source.setType(configured.type());
        source.setEnabled(true);
        source.setBar(configured.bar());
        source.setParams(merged);
        return configure(source, configured.strategy());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ConfiguredTradingStrategy<?> configure(
            TradingProperties.StrategyInstanceProperties item,
            TradingStrategy strategy
    ) {
        StrategyConfig config = (StrategyConfig) objectMapper.convertValue(
                item.getParams() == null ? Map.of() : item.getParams(),
                strategy.configType()
        );
        String id = item.getId() == null || item.getId().isBlank() ? item.getType() : item.getId();
        return new ConfiguredTradingStrategy<>(
                id,
                item.getType(),
                item.getBar() == null || item.getBar().isBlank() ? "1m" : item.getBar(),
                strategy,
                config,
                item
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
