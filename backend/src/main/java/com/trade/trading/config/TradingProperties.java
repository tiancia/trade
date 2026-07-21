package com.trade.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "trade.trading")
public class TradingProperties {
    /** Enables scheduled trading decisions and event scans. */
    private boolean enabled = true;
    /** OKX instrument identifier, for example BTC-USDT. */
    private String instId = "BTC-USDT";
    /** OKX instrument type such as SPOT, SWAP, FUTURES, or OPTION. */
    private String instType = "SPOT";
    /** Base currency used for balances and sell sizing. */
    private String baseCcy = "BTC";
    /** Quote currency used for balances and buy sizing. */
    private String quoteCcy = "USDT";
    /** OKX trade mode; spot trading normally uses cash. */
    private String tdMode = "cash";
    /** Derivative position mode: long_short or net. */
    private String positionMode = "long_short";
    /** Broker mode used by the strategy engine. */
    private ExecutionMode executionMode = ExecutionMode.PAPER;
    /** Explicit second guard required before live orders are allowed. */
    private boolean liveEnabled = false;
    /** Browser origins allowed to call the trading operator API. */
    private List<String> frontendAllowedOriginPatterns = List.of("http://localhost:*");
    /** Maximum quote-currency amount for one spot buy. */
    private BigDecimal maxBuyQuoteAmount = new BigDecimal("10");
    /** Maximum fraction of the available base balance for one spot sell. */
    private BigDecimal maxSellPositionRatio = BigDecimal.ONE;
    /** Maximum OKX size for one derivative order. */
    private BigDecimal maxDerivativeOrderSize = BigDecimal.ONE;
    /** Delay between scheduled decisions, in milliseconds. */
    private long decisionFixedDelayMs = 1_800_000L;
    /** Delay between market event scans, in milliseconds. */
    private long eventScanFixedDelayMs = 60_000L;
    /** Initial delay before the first scheduled decision, in milliseconds. */
    private long initialDelayMs = 30_000L;
    /** Initial delay before the first event scan, in milliseconds. */
    private long eventInitialDelayMs = 30_000L;
    /** Cooldown after an event-triggered decision, in milliseconds. */
    private long eventCooldownMs = 600_000L;
    /** WebSocket subscription and in-memory cache settings. */
    private WebSocketProperties websocket = new WebSocketProperties();
    /** Bounded asynchronous pipeline shared by every market-data producer. */
    private EventQueueProperties eventQueue = new EventQueueProperties();
    /** Database persistence settings for public market data. */
    private MarketDataPersistenceProperties marketDataPersistence = new MarketDataPersistenceProperties();
    /** Redis-backed cache settings for frequently read public market data. */
    private HotMarketDataCacheProperties hotMarketDataCache = new HotMarketDataCacheProperties();
    /** Price-change ratio that triggers an event decision. */
    private BigDecimal priceMoveTriggerPercent = new BigDecimal("0.02");
    /** Current-volume multiple over lookback average that triggers an event. */
    private BigDecimal volumeSpikeMultiplier = new BigDecimal("3");
    /** Tracked-position loss ratio that triggers an event decision. */
    private BigDecimal floatingLossTriggerPercent = new BigDecimal("0.10");
    /** Estimated one-way taker fee ratio used by decision guards. */
    private BigDecimal takerFeeRate = new BigDecimal("0.001");
    /** Minimum expected net edge ratio after spread and fees. */
    private BigDecimal minExpectedNetEdgePercent = new BigDecimal("0.001");
    /** JSON file used for local strategy and risk state. */
    private String stateFile = "data/trading-state.json";
    /** Maximum number of recent decisions retained in local state. */
    private int recentDecisionMemoryLimit = 20;
    /** Number of levels requested on each side of the order book. */
    private int orderBookDepth = 5;
    /** Number of one-minute candles requested for a decision. */
    private int oneMinuteCandleLimit = 25;
    /** Number of five-minute candles requested for a decision. */
    private int fiveMinuteCandleLimit = 24;
    /** Number of recent and pending orders requested from OKX. */
    private int recentOrderLimit = 10;
    /** Number of recent fills requested from OKX. */
    private int recentFillLimit = 10;
    /** Number of post-order fill status queries. */
    private int orderFillQueryAttempts = 5;
    /** Delay between fill status queries, in milliseconds. */
    private long orderFillQueryDelayMs = 1_000L;
    /** Decimal scale applied to quote-currency order amounts. */
    private int quoteAmountScale = 2;
    /** Long-lived strategy profile and legacy decision thresholds. */
    private StrategyProperties strategy = new StrategyProperties();
    /** Configured deterministic strategy instances. */
    private List<StrategyInstanceProperties> strategies = List.of(defaultThresholdStrategy());
    /** Application-side risk control thresholds. */
    private RiskProperties risk = new RiskProperties();

    public boolean isSpotInstrument() {
        return "SPOT".equalsIgnoreCase(instType);
    }

    public boolean isDerivativeInstrument() {
        return "SWAP".equalsIgnoreCase(instType)
                || "FUTURES".equalsIgnoreCase(instType)
                || "OPTION".equalsIgnoreCase(instType);
    }

    public boolean isLongShortPositionMode() {
        return "long_short".equalsIgnoreCase(positionMode)
                || "long-short".equalsIgnoreCase(positionMode);
    }

    public boolean isShortEnabled() {
        return strategy != null && strategy.isAllowShort();
    }

    public boolean isLiveExecutionAllowed() {
        return executionMode == ExecutionMode.LIVE && liveEnabled;
    }

    private static StrategyInstanceProperties defaultThresholdStrategy() {
        StrategyInstanceProperties strategy = new StrategyInstanceProperties();
        strategy.setId("threshold-event-default");
        strategy.setType("threshold-event");
        strategy.setEnabled(true);
        strategy.setBar("1m");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("price-move-window-candles", 5);
        params.put("volume-lookback-candles", 20);
        strategy.setParams(params);
        return strategy;
    }

    public enum ExecutionMode {
        PAPER,
        LIVE,
        BACKTEST
    }

    @Data
    public static class WebSocketProperties {
        /** Enables public OKX ticker and candle subscriptions. */
        private boolean enabled = true;
        /** Age after which cached WebSocket data is considered stale. */
        private long staleTimeoutMs = 120_000L;
        /** Maximum number of one-minute candles retained in memory. */
        private int candleCacheLimit = 100;
    }

    @Data
    public static class EventQueueProperties {
        /** Maximum number of events waiting for the consumer. */
        private int capacity = 1_024;
        /** Backpressure behavior used when the bounded queue is full. */
        private EventQueueFullPolicy fullPolicy = EventQueueFullPolicy.DROP_OLDEST;
        /** Maximum producer wait for BLOCK policy, in milliseconds. */
        private long publishTimeoutMs = 100L;
        /** Consumer polling interval, in milliseconds. */
        private long pollTimeoutMs = 100L;
        /** Maximum time allowed for draining queued events during shutdown. */
        private long shutdownTimeoutMs = 5_000L;
    }

    public enum EventQueueFullPolicy {
        /** Discards the oldest queued event so fresher market data can be retained. */
        DROP_OLDEST,
        /** Discards the newly received event and never blocks its producer. */
        DROP_LATEST,
        /** Waits up to publish-timeout-ms for consumer capacity. */
        BLOCK
    }

    @Data
    public static class MarketDataPersistenceProperties {
        /** Master switch for storing ticker, order-book, and candle data. */
        private boolean enabled = true;
        /** Stores ticker payloads and queryable top-of-book values. */
        private boolean tickerEnabled = true;
        /** Stores order-book payloads collected by REST decisions. */
        private boolean orderBookEnabled = true;
        /** Upserts REST and WebSocket candles into okx_candle_cache. */
        private boolean candleEnabled = true;
        /** Minimum interval between WebSocket ticker inserts, in milliseconds. */
        private long websocketTickerMinIntervalMs = 5_000L;
    }

    @Data
    public static class HotMarketDataCacheProperties {
        /** Enables best-effort Redis caching for public market data. */
        private boolean enabled = true;
        /** Namespace used for every trading market-data key. */
        private String keyPrefix = "trade:trading:hot-market";
        /** TTL for latest ticker and order-book snapshots, in milliseconds. */
        private long snapshotTtlMs = 120_000L;
        /** TTL for each instrument/bar candle sorted set, in milliseconds. */
        private long candleTtlMs = 10_800_000L;
        /** Maximum number of recent candles retained per instrument and bar. */
        private int candleLimit = 200;
        /** Cooldown before retrying Redis after a connection or command failure. */
        private long failureRetryIntervalMs = 5_000L;
    }

    @Data
    public static class StrategyProperties {
        /** Primary capital and return objective retained in the decision context. */
        private String objective = "Preserve USDT-denominated capital first, then compound account equity through only high-conviction, cost-adjusted BTC setups.";
        /** Expected review frequency and position holding horizon. */
        private String horizon = "review every 30-60 minutes; hold a valid thesis for 4 hours to 7 days unless invalidated";
        /** Human-readable risk posture retained in the strategy context. */
        private String riskProfile = "conservative";
        /** Maximum strategy drawdown ratio. */
        private BigDecimal maxDrawdownRatio = new BigDecimal("0.05");
        /** Minimum accepted reward-to-risk ratio for a non-HOLD action. */
        private BigDecimal minRiskRewardRatio = new BigDecimal("1.5");
        /** Legacy minimum estimated win probability. */
        private BigDecimal minWinProbability = new BigDecimal("0.56");
        /** Legacy minimum decision confidence. */
        private BigDecimal minConfidence = new BigDecimal("0.65");
        /** Minimum product of win probability and confidence. */
        private BigDecimal minWinConfidenceScore = new BigDecimal("0.40");
        /** Allows short actions for derivative instruments. */
        private boolean allowShort = false;
        /** Ordered qualitative rules retained for decision-policy compatibility. */
        private List<String> principles = List.of(
                "HOLD is the default action; trade only when all non-HOLD decision gates pass.",
                "Preserve capital and avoid churn before pursuing incremental return.",
                "Keep decisions consistent with the active strategy thesis unless the invalidation condition is met.",
                "Open or add exposure only when expected net edge, risk-reward, win probability, and confidence all clear their configured thresholds.",
                "Reduce exposure when the thesis is invalidated or drawdown pressure threatens the objective."
        );
    }

    @Data
    public static class StrategyInstanceProperties {
        /** Stable identifier used in logs, state, and backtests. */
        private String id;
        /** Registered strategy implementation type. */
        private String type;
        /** Enables this strategy instance. */
        private boolean enabled = true;
        /** Candle interval evaluated by this strategy. */
        private String bar = "1m";
        /** Strategy-specific parameters bound by its configuration parser. */
        private Map<String, Object> params = new LinkedHashMap<>();
    }

    @Data
    public static class RiskProperties {
        /** Master switch for application-side risk controls. */
        private boolean enabled = true;
        /** Consecutive losing closes allowed before cooldown. */
        private int maxConsecutiveLosses = 3;
        /** Cooldown after reaching the loss limit, in milliseconds. */
        private long lossCooldownMs = 3_600_000L;
        /** Maximum drawdown from the tracked equity high watermark. */
        private BigDecimal maxDrawdownRatio = new BigDecimal("0.20");
        /** Maximum loss from the current trading day's starting equity. */
        private BigDecimal maxDailyLossRatio = new BigDecimal("0.05");
        /** Time zone used to determine trading-day boundaries. */
        private String dailyZone = "Asia/Shanghai";
        /** Minimum interval between exposure-increasing actions. */
        private long minOpenIntervalMs = 600_000L;
        /** Maximum consecutive exposure-increasing actions. */
        private int maxConsecutiveOpenActions = 2;
        /** Maximum estimated equity fraction opened by one action. */
        private BigDecimal maxSingleOpenEquityRatio = new BigDecimal("0.10");
        /** Ratio below which equity changes are ignored as numerical noise. */
        private BigDecimal equityNoiseRatio = new BigDecimal("0.0001");
    }
}
