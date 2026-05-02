package com.trade.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Data
@ConfigurationProperties(prefix = "trade.trading")
public class TradingProperties {
    private boolean enabled = true;
    private String instId = "BTC-USDT";
    private String instType = "SPOT";
    private String baseCcy = "BTC";
    private String quoteCcy = "USDT";
    private String tdMode = "cash";
    private BigDecimal maxBuyQuoteAmount = new BigDecimal("10");
    private BigDecimal maxSellPositionRatio = BigDecimal.ONE;
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

    @Data
    public static class WebSocketProperties {
        private boolean enabled = true;
        private long staleTimeoutMs = 120_000L;
        private int candleCacheLimit = 100;
    }
}
