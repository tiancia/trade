package com.trade.polymarket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "trade.polymarket")
public class AiPolymarketProperties {
    private boolean enabled = false;
    private long decisionFixedDelayMs = 1_800_000L;
    private long initialDelayMs = 60_000L;
    private int marketLimit = 5;
    private int marketDiscoveryWindow = 50;
    private MarketDiscoverySource marketDiscoverySource = MarketDiscoverySource.GAMMA_VOLUME;
    private String samplingMarketsInitialCursor;
    private boolean requireMarketEndDate = false;
    private long minTimeToResolutionMinutes = 0L;
    private long maxTimeToResolutionHours = 0L;
    private BigDecimal minMarketVolume24hr = BigDecimal.ZERO;
    private BigDecimal minMarketLiquidity = BigDecimal.ZERO;
    private BigDecimal maxOutcomeSpread = BigDecimal.ZERO;
    private BigDecimal minOutcomeAskLiquidityUsdc = BigDecimal.ZERO;
    private List<String> marketSlugs = new ArrayList<>();
    private List<String> marketIds = new ArrayList<>();
    private List<String> clobTokenIds = new ArrayList<>();
    private boolean requireAcceptingOrders = true;
    private int orderBookDepth = 5;
    private BigDecimal maxOrderUsdc = new BigDecimal("5");
    private BigDecimal minWinConfidenceScore = new BigDecimal("0.50");
    private BigDecimal minExpectedEdge = new BigDecimal("0.03");
    private BigDecimal minLimitPrice = new BigDecimal("0.01");
    private BigDecimal maxLimitPrice = new BigDecimal("0.95");
    private BigDecimal minOrderSize = new BigDecimal("5");
    private ExecutionProperties execution = new ExecutionProperties();

    public enum MarketDiscoverySource {
        GAMMA_VOLUME,
        CLOB_SAMPLING
    }

    @Data
    public static class ExecutionProperties {
        private boolean enabled = false;
        private String pythonCommand = "python";
        private String scriptPath = "../tools/polymarket/polymarket_place_order.py";
        private long timeoutMs = 60_000L;
        private int chainId = 137;
        private int signatureType = 1;
        private String orderType = "FAK";
        private String privateKey;
        private String apiKey;
        private String apiSecret;
        private String apiPassphrase;
        private String funderAddress;
        private String privateKeyEnvName = "POLYMARKET_PRIVATE_KEY";
        private String apiKeyEnvName = "POLYMARKET_API_KEY";
        private String apiSecretEnvName = "POLYMARKET_API_SECRET";
        private String apiPassphraseEnvName = "POLYMARKET_API_PASSPHRASE";
        private String funderAddressEnvName = "POLYMARKET_FUNDER_ADDRESS";
        private boolean geoblockCheckEnabled = true;
        private String geoblockUrl = "https://polymarket.com/api/geoblock";
    }
}
