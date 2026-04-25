package com.trade.trading;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Data
@Component
@ConfigurationProperties(prefix = "trade.ai")
public class AiTradingProperties {
    private boolean enabled = true;
    private String instId = "BTC-USDT";
    private String instType = "SPOT";
    private String baseCcy = "BTC";
    private String quoteCcy = "USDT";
    private String tdMode = "cash";
    private BigDecimal maxBuyQuoteAmount = new BigDecimal("100");
    private BigDecimal maxSellPositionRatio = BigDecimal.ONE;
    private long decisionFixedDelayMs = 1_800_000L;
    private long eventScanFixedDelayMs = 60_000L;
    private long initialDelayMs = 30_000L;
    private long eventInitialDelayMs = 30_000L;
    private long eventCooldownMs = 600_000L;
    private BigDecimal priceMoveTriggerPercent = new BigDecimal("0.02");
    private BigDecimal volumeSpikeMultiplier = new BigDecimal("3");
    private BigDecimal floatingLossTriggerPercent = new BigDecimal("0.10");
    private String stateFile = "data/trading-state.json";
    private int orderBookDepth = 5;
    private int oneMinuteCandleLimit = 25;
    private int fiveMinuteCandleLimit = 24;
    private int recentOrderLimit = 10;
    private int recentFillLimit = 10;
    private int orderFillQueryAttempts = 5;
    private long orderFillQueryDelayMs = 1_000L;
    private int quoteAmountScale = 2;
}
