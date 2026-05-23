package com.trade.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "trade.trading")
public class TradingProperties {
    private boolean enabled = true;
    private String instId = "BTC-USDT";
    private String instType = "SPOT";
    private String baseCcy = "BTC";
    private String quoteCcy = "USDT";
    private String tdMode = "cash";
    private String positionMode = "long_short";
    private BigDecimal maxBuyQuoteAmount = new BigDecimal("10");
    private BigDecimal maxSellPositionRatio = BigDecimal.ONE;
    private BigDecimal maxDerivativeOrderSize = BigDecimal.ONE;
    private long decisionFixedDelayMs = 1_800_000L;
    private long eventScanFixedDelayMs = 60_000L;
    private long initialDelayMs = 30_000L;
    private long eventInitialDelayMs = 30_000L;
    private long eventCooldownMs = 600_000L;
    private WebSocketProperties websocket = new WebSocketProperties();
    private BigDecimal priceMoveTriggerPercent = new BigDecimal("0.02");
    private BigDecimal volumeSpikeMultiplier = new BigDecimal("3");
    private BigDecimal floatingLossTriggerPercent = new BigDecimal("0.10");
    private BigDecimal takerFeeRate = new BigDecimal("0.001");
    private BigDecimal minExpectedNetEdgePercent = new BigDecimal("0.001");
    private String stateFile = "data/trading-state.json";
    private int recentDecisionMemoryLimit = 20;
    private int orderBookDepth = 5;
    private int oneMinuteCandleLimit = 25;
    private int fiveMinuteCandleLimit = 24;
    private int recentOrderLimit = 10;
    private int recentFillLimit = 10;
    private int orderFillQueryAttempts = 5;
    private long orderFillQueryDelayMs = 1_000L;
    private int quoteAmountScale = 2;
    private StrategyProperties strategy = new StrategyProperties();
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

    @Data
    public static class WebSocketProperties {
        private boolean enabled = true;
        private long staleTimeoutMs = 120_000L;
        private int candleCacheLimit = 100;
    }

    @Data
    public static class StrategyProperties {
        private String objective = "Preserve USDT-denominated capital first, then compound account equity through only high-conviction, cost-adjusted BTC setups.";
        private String horizon = "review every 30-60 minutes; hold a valid thesis for 4 hours to 7 days unless invalidated";
        private String riskProfile = "conservative";
        private BigDecimal maxDrawdownRatio = new BigDecimal("0.05");
        private BigDecimal minRiskRewardRatio = new BigDecimal("1.5");
        private BigDecimal minWinProbability = new BigDecimal("0.56");
        private BigDecimal minConfidence = new BigDecimal("0.65");
        private BigDecimal minWinConfidenceScore = new BigDecimal("0.40");
        private boolean allowShort = false;
        private List<String> principles = List.of(
                "HOLD is the default action; trade only when all non-HOLD decision gates pass.",
                "Preserve capital and avoid churn before pursuing incremental return.",
                "Keep decisions consistent with the active strategy thesis unless the invalidation condition is met.",
                "Open or add exposure only when expected net edge, risk-reward, win probability, and confidence all clear their configured thresholds.",
                "Reduce exposure when the thesis is invalidated or drawdown pressure threatens the objective."
        );
    }

    @Data
    public static class RiskProperties {
        private boolean enabled = true;
        private int maxConsecutiveLosses = 3;
        private long lossCooldownMs = 3_600_000L;
        private BigDecimal maxDrawdownRatio = new BigDecimal("0.20");
        private BigDecimal maxDailyLossRatio = new BigDecimal("0.05");
        private String dailyZone = "Asia/Shanghai";
        private long minOpenIntervalMs = 600_000L;
        private int maxConsecutiveOpenActions = 2;
        private BigDecimal maxSingleOpenEquityRatio = new BigDecimal("0.10");
        private BigDecimal equityNoiseRatio = new BigDecimal("0.0001");
    }
}
