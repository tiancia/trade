package com.trade.polymarket.infrastructure.config;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "trade.polymarket")
public class AiPolymarketProperties {
    private volatile boolean enabled = false;
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private long moduleRevision;
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
    private volatile ExecutionProperties execution = new ExecutionProperties();

    public record DecisionRun(long moduleRevision, long executionRevision, boolean live) {}

    public synchronized void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            moduleRevision++;
        }
    }

    public synchronized void invalidateDecisions() {
        moduleRevision++;
    }

    public synchronized void setExecution(ExecutionProperties execution) {
        if (this.execution != execution) {
            this.execution = java.util.Objects.requireNonNull(execution);
            moduleRevision++;
        }
    }

    public synchronized DecisionRun decisionRun() {
        return execution.snapshot(moduleRevision);
    }

    public synchronized boolean isCurrent(DecisionRun run) {
        return enabled && run.equals(execution.snapshot(moduleRevision));
    }

    public enum MarketDiscoverySource {
        GAMMA_VOLUME,
        CLOB_SAMPLING
    }

    @Data
    public static class ExecutionProperties {
        private volatile boolean enabled = false;
        @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
        private long revision;

        public synchronized void setEnabled(boolean enabled) {
            if (this.enabled != enabled) {
                this.enabled = enabled;
                revision++;
            }
        }

        private synchronized DecisionRun snapshot(long moduleRevision) {
            return new DecisionRun(moduleRevision, revision, enabled);
        }

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
    /** Resolves configuration into adapter-neutral market eligibility limits. */
    public com.trade.polymarket.domain.rule.MarketEligibilityPolicy marketEligibilityPolicy() {
        return new com.trade.polymarket.domain.rule.MarketEligibilityPolicy(
                requireMarketEndDate, minTimeToResolutionMinutes, maxTimeToResolutionHours,
                minMarketVolume24hr, minMarketLiquidity, maxOutcomeSpread, minOutcomeAskLiquidityUsdc);
    }
}
