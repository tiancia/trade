package com.trade.trading.application.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.trade.trading.application.port.StrategyConfigurationStore;
import com.trade.trading.domain.model.StrategyConfigurationVersion;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TradingStrategyRegistry {
    private final Map<String, TradingStrategy<? extends StrategyConfig>> strategies;
    private final TradingProperties properties;
    private final ObjectMapper objectMapper;
    private final StrategyConfigurationStore configurationStore;

    /** Test-only construction without shared configuration persistence. */
    public TradingStrategyRegistry(
            List<TradingStrategy<? extends StrategyConfig>> strategies,
            TradingProperties properties
    ) {
        this(strategies, properties, null);
    }

    @Autowired
    public TradingStrategyRegistry(
            List<TradingStrategy<? extends StrategyConfig>> strategies,
            TradingProperties properties,
            StrategyConfigurationStore configurationStore
    ) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(
                        strategy -> normalize(strategy.type()),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        this.properties = properties;
        this.configurationStore = configurationStore;
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
            StrategyConfigurationVersion baseline = baseline(item, strategy);
            StrategyConfigurationVersion snapshot = configurationStore == null
                    ? baseline : configurationStore.currentOrCreate(baseline);
            if (!normalize(snapshot.type()).equals(normalize(item.getType()))
                    || !snapshot.bar().equals(baseline.bar())) {
                throw new IllegalStateException("Persisted strategy type/bar differs from deployment: " + baseline.strategyId());
            }
            result.add(configure(item, strategy, snapshot));
        }
        return result;
    }

    public List<TradingProperties.StrategyInstanceProperties> strategySummaries() {
        List<TradingProperties.StrategyInstanceProperties> configured = properties.getStrategies();
        if (configured == null || configured.isEmpty()) return List.of();
        Map<String, TradingProperties.StrategyInstanceProperties> effective = configuredStrategies().stream()
                .collect(Collectors.toMap(ConfiguredTradingStrategy::id, ConfiguredTradingStrategy::source));
        return configured.stream().map(item -> {
            if (item == null || !item.isEnabled()) return item;
            String id = item.getId() == null || item.getId().isBlank() ? item.getType() : item.getId();
            return effective.getOrDefault(id, item);
        }).toList();
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
        // Backtest overrides are resolved once, including the same deployment
        // fallbacks used by evaluation, without changing the persisted version.
        Map<String, Object> effective = baseline(source, configured.strategy()).params();
        StrategyConfigurationVersion snapshot = new StrategyConfigurationVersion(
                configured.id(), configured.type(), configured.bar(), configured.configuration().version(),
                configured.configuration().changedAt(), configured.configuration().reason(), effective);
        return configure(source, configured.strategy(), snapshot);
    }

    public StrategyConfigurationVersion configuration(String strategyId) {
        return configuredStrategy(strategyId).configuration();
    }

    public List<StrategyConfigurationVersion> configurationHistory(String strategyId) {
        return configurationHistory(strategyId, 0, 100);
    }

    public List<StrategyConfigurationVersion> configurationHistory(String strategyId, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("History offset must be nonnegative and limit must be from 1 to 500");
        }
        StrategyConfigurationVersion current = configuration(strategyId);
        return configurationStore == null ? (offset == 0 ? List.of(current) : List.of())
                : configurationStore.history(current.strategyId(), offset, limit);
    }

    public StrategyConfigurationVersion updateConfiguration(
            String strategyId, Long expectedVersion, String reason, Map<String, Object> params
    ) {
        if (configurationStore == null) {
            throw new IllegalStateException("Shared strategy configuration storage is required");
        }
        if (expectedVersion == null || expectedVersion < 1 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("expectedVersion must be a positive configuration version");
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 1000) {
            throw new IllegalArgumentException("reason is required and must not exceed 1000 characters");
        }
        ConfiguredTradingStrategy<?> configured = configuredStrategy(strategyId);
        boolean intraday = IntradayTechnicalStrategy.TYPE.equals(normalize(configured.type()));
        if (!intraday && !ThresholdEventStrategy.TYPE.equals(normalize(configured.type()))) {
            throw new IllegalArgumentException("Runtime parameter editing is unavailable for this strategy type");
        }
        Map<String, Object> validated = intraday ? validateIntradayParameters(params, configured.bar(), true)
                : validateParameters(params, configured.bar());
        StrategyConfigurationVersion next = new StrategyConfigurationVersion(
                configured.id(), configured.type(), configured.bar(), expectedVersion + 1,
                Instant.now().toString(), reason.trim(), validated);
        return configurationStore.update(expectedVersion, next);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ConfiguredTradingStrategy<?> configure(
            TradingProperties.StrategyInstanceProperties item,
            TradingStrategy strategy,
            StrategyConfigurationVersion snapshot
    ) {
        StrategyConfig config = (StrategyConfig) objectMapper.convertValue(
                snapshot.params(),
                strategy.configType()
        );
        String id = item.getId() == null || item.getId().isBlank() ? item.getType() : item.getId();
        TradingProperties.StrategyInstanceProperties source = new TradingProperties.StrategyInstanceProperties();
        source.setId(id);
        source.setType(item.getType());
        source.setEnabled(item.isEnabled());
        source.setBar(snapshot.bar());
        source.setParams(new LinkedHashMap<>(snapshot.params()));
        return new ConfiguredTradingStrategy<>(
                id,
                item.getType(),
                item.getBar() == null || item.getBar().isBlank() ? "1m" : item.getBar(),
                strategy,
                config,
                source,
                snapshot
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private StrategyConfigurationVersion baseline(TradingProperties.StrategyInstanceProperties item, TradingStrategy strategy) {
        if (IntradayTechnicalStrategy.TYPE.equals(normalize(strategy.type()))) {
            Map<String, Object> params = validateIntradayParameters(item.getParams(), item.getBar(), false);
            String id = item.getId() == null || item.getId().isBlank() ? item.getType() : item.getId();
            return new StrategyConfigurationVersion(id, item.getType(), "15m", 1,
                    Instant.now().toString(), "Deployment candidate configuration; validate out of sample before adoption", params);
        }
        StrategyConfig raw = (StrategyConfig) objectMapper.convertValue(
                item.getParams() == null ? Map.of() : item.getParams(), strategy.configType());
        Map<String, Object> params;
        if (raw instanceof ThresholdEventStrategyConfig threshold) {
            ThresholdEventStrategyConfig effective = ThresholdEventStrategy.normalize(threshold, properties);
            if (effective.getBuyQuoteAmount() == null || effective.getBuyQuoteAmount().signum() <= 0) {
                effective.setBuyQuoteAmount(properties.getMaxBuyQuoteAmount());
            }
            if (effective.getOrderSize() == null || effective.getOrderSize().signum() <= 0) {
                effective.setOrderSize(properties.getMaxDerivativeOrderSize());
            }
            if (effective.getSellBaseAmount() != null && effective.getSellBaseAmount().signum() <= 0) {
                effective.setSellBaseAmount(null);
            }
            effective.setPriceMoveWindowCandles(Math.max(2, effective.getPriceMoveWindowCandles()));
            effective.setVolumeLookbackCandles(Math.max(1, effective.getVolumeLookbackCandles()));
            params = objectMapper.convertValue(effective, Map.class);
        } else {
            // Other registered strategies own their parameter shape. In
            // particular an empty marker config need not be serializable.
            params = new LinkedHashMap<>(item.getParams() == null ? Map.of() : item.getParams());
        }
        String id = item.getId() == null || item.getId().isBlank() ? item.getType() : item.getId();
        String bar = item.getBar() == null || item.getBar().isBlank() ? "1m" : item.getBar();
        return new StrategyConfigurationVersion(id, item.getType(), bar, 1,
                Instant.now().toString(), "Deployment configuration", params);
    }

    private Map<String, Object> validateParameters(Map<String, Object> params, String bar) {
        Set<String> allowed = Set.of("price-move-trigger-percent", "volume-spike-multiplier",
                "floating-loss-trigger-percent", "buy-quote-amount", "sell-base-amount", "order-size",
                "price-move-window-candles", "volume-lookback-candles", "require-confirmed-candle");
        if (params == null || !params.keySet().equals(allowed)) {
            throw new IllegalArgumentException("params must contain exactly the supported strategy parameter keys: " + allowed);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price-move-trigger-percent", decimal(params, "price-move-trigger-percent", BigDecimal.ONE, false));
        result.put("floating-loss-trigger-percent", decimal(params, "floating-loss-trigger-percent", BigDecimal.ONE, false));
        result.put("volume-spike-multiplier", decimal(params, "volume-spike-multiplier", new BigDecimal("10000"), false));
        BigDecimal amountLimit = new BigDecimal("1000000000000000");
        result.put("buy-quote-amount", decimal(params, "buy-quote-amount", amountLimit, false));
        result.put("sell-base-amount", decimal(params, "sell-base-amount", amountLimit, true));
        result.put("order-size", decimal(params, "order-size", amountLimit, false));
        Object confirmed = params.get("require-confirmed-candle");
        if (!(confirmed instanceof Boolean)) {
            throw new IllegalArgumentException("require-confirmed-candle must be a boolean");
        }
        result.put("require-confirmed-candle", confirmed);
        int collected = switch (bar.toLowerCase(Locale.ROOT)) {
            case "1m" -> properties.getOneMinuteCandleLimit();
            case "5m" -> properties.getFiveMinuteCandleLimit();
            case "15m" -> properties.getFifteenMinuteCandleLimit();
            default -> throw new IllegalArgumentException("Unsupported decision candle bar: " + bar);
        };
        int usable = Math.min(1000, collected) - (Boolean.TRUE.equals(confirmed) ? 1 : 0);
        result.put("price-move-window-candles", integer(params, "price-move-window-candles", 2, usable));
        result.put("volume-lookback-candles", integer(params, "volume-lookback-candles", 1, usable - 1));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateIntradayParameters(Map<String, Object> input, String bar, boolean complete) {
        if (!"15m".equals(bar)) throw new IllegalArgumentException("intraday-technical requires bar=15m");
        Map<String, Object> defaults = objectMapper.convertValue(new IntradayTechnicalStrategyConfig(), Map.class);
        Map<String, Object> supplied = input == null ? Map.of() : input;
        if (!defaults.keySet().containsAll(supplied.keySet()) || (complete && !supplied.keySet().equals(defaults.keySet()))) {
            throw new IllegalArgumentException("params must use the supported intraday parameter keys: " + defaults.keySet());
        }
        Map<String, Object> result = new LinkedHashMap<>(defaults);
        result.putAll(supplied);
        for (String key : List.of("ema-fast-period", "ema-slow-period", "rsi-period", "atr-period", "adx-period",
                "bollinger-period", "breakout-period", "volume-period")) {
            result.put(key, integer(result, key, 2, 100));
        }
        for (String key : List.of("bollinger-deviations", "trend-min-adx", "range-max-adx", "entry-rsi-min", "entry-rsi-max",
                "min-volume-ratio", "max-entry-extension-atr", "buy-quote-amount")) {
            result.put(key, decimal(result, key, key.equals("buy-quote-amount") ? new BigDecimal("1000000000000000")
                    : key.equals("bollinger-deviations") ? new BigDecimal("5") : new BigDecimal("100"), false));
        }
        for (String key : List.of("min-atr-percent", "max-atr-percent", "stop-loss-percent", "take-profit-percent", "min-net-reward-percent")) {
            result.put(key, decimal(result, key, new BigDecimal("0.5"), false));
        }
        // Zero slippage is a valid explicit assumption, with strict numeric parsing.
        Object slippage = result.get("estimated-slippage-rate");
        if ((slippage instanceof Number || slippage instanceof String) && slippage.toString().length() <= 100) {
            try {
                BigDecimal value = new BigDecimal(slippage.toString());
                if (value.signum() < 0 || value.compareTo(new BigDecimal("0.1")) > 0 || value.scale() > 18) throw new NumberFormatException();
                result.put("estimated-slippage-rate", value);
            } catch (NumberFormatException e) { throw new IllegalArgumentException("estimated-slippage-rate must be between 0 and 0.1"); }
        } else throw new IllegalArgumentException("estimated-slippage-rate must be numeric");
        if (!(result.get("mode") instanceof String mode)) throw new IllegalArgumentException("mode must be a supported setup name");
        try { com.trade.trading.domain.strategy.IntradayTechnicalPolicy.Mode.valueOf(mode); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unsupported intraday mode: " + mode); }
        IntradayTechnicalStrategyConfig config = objectMapper.convertValue(result, IntradayTechnicalStrategyConfig.class);
        if (config.getEmaFastPeriod() >= config.getEmaSlowPeriod()
                || config.getEntryRsiMin().compareTo(config.getEntryRsiMax()) >= 0
                || config.getMinAtrPercent().compareTo(config.getMaxAtrPercent()) >= 0
                || config.getTakeProfitPercent().compareTo(config.getStopLossPercent()) <= 0) {
            throw new IllegalArgumentException("Require fast EMA < slow EMA, RSI min < max, ATR min < max, target > stop");
        }
        if (config.warmupCandles() > properties.getFifteenMinuteCandleLimit() - 1) {
            throw new IllegalArgumentException("Indicator warmup exceeds confirmed 15m candle collection capacity");
        }
        return result;
    }

    private static BigDecimal decimal(Map<String, Object> params, String key, BigDecimal maximum, boolean nullable) {
        Object input = params.get(key);
        if (input == null && nullable) { return null; }
        if (!(input instanceof Number) && !(input instanceof String)) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        try {
            String value = input.toString();
            if (value.length() > 100) { throw new NumberFormatException(); }
            BigDecimal decimal = new BigDecimal(value);
            if (decimal.signum() <= 0 || decimal.compareTo(maximum) > 0 || decimal.scale() > 18) {
                throw new NumberFormatException();
            }
            return decimal;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be greater than 0 and at most " + maximum + " (up to 18 decimal places)");
        }
    }

    private static int integer(Map<String, Object> params, String key, int minimum, int maximum) {
        Object input = params.get(key);
        if (!(input instanceof Number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        try {
            int value = new BigDecimal(input.toString()).intValueExact();
            if (value < minimum || value > maximum) { throw new ArithmeticException(); }
            return value;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer from " + minimum + " to " + maximum
                    + " within the configured candle collection capacity");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
