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
    private List<String> marketSlugs = new ArrayList<>();
    private List<String> marketIds = new ArrayList<>();
    private List<String> clobTokenIds = new ArrayList<>();
    private boolean requireAcceptingOrders = true;
    private int orderBookDepth = 5;
    private BigDecimal maxOrderUsdc = new BigDecimal("5");
    private BigDecimal minConfidence = new BigDecimal("0.65");
    private BigDecimal minExpectedEdge = new BigDecimal("0.03");
    private BigDecimal minLimitPrice = new BigDecimal("0.01");
    private BigDecimal maxLimitPrice = new BigDecimal("0.95");
    private BigDecimal minOrderSize = new BigDecimal("5");
    private ExecutionProperties execution = new ExecutionProperties();

    @Data
    public static class ExecutionProperties {
        private boolean enabled = false;
        private String pythonCommand = "python";
        private String scriptPath = "scripts/polymarket_place_order.py";
        private long timeoutMs = 60_000L;
        private int chainId = 137;
        private int signatureType = 0;
        private String orderType = "FAK";
        private String privateKeyEnvName = "POLYMARKET_PRIVATE_KEY";
        private String apiKeyEnvName = "POLYMARKET_API_KEY";
        private String apiSecretEnvName = "POLYMARKET_API_SECRET";
        private String apiPassphraseEnvName = "POLYMARKET_API_PASSPHRASE";
        private String funderAddressEnvName = "POLYMARKET_FUNDER_ADDRESS";
        private boolean geoblockCheckEnabled = true;
        private String geoblockUrl = "https://polymarket.com/api/geoblock";
    }
}
