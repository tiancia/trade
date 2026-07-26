package com.trade.trading.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingPositionState;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.model.TradingState;
import com.trade.trading.model.TradingStrategyState;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

/**
 * Composes local strategy memory with authoritative MySQL financial state.
 *
 * <p>The JSON document retains only strategy selection, strategy thesis, and
 * bounded decision memory. Position, cost, and risk reads and writes always go
 * through {@link TradingFinancialStateStore}. On first startup after upgrade,
 * legacy financial values in the JSON file seed otherwise-empty MySQL rows;
 * subsequent JSON writes omit those values.</p>
 */
@Component
public class TradingStateRepository {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final Path statePath;
    private final TradingFinancialStateStore financialStateStore;
    private final String accountScope;
    private final String instId;
    private TradingMemoryDocument memory;
    private TradingState legacySeed;

    @Autowired
    public TradingStateRepository(
            TradingProperties properties,
            TradingFinancialStateStore financialStateStore
    ) {
        this(
                Path.of(properties.getStateFile()),
                financialStateStore,
                properties.financialAccountScope(),
                properties.getInstId()
        );
    }

    /** Unit-test constructor; production always injects the MySQL store. */
    public TradingStateRepository(Path statePath) {
        this(statePath, new InMemoryTradingFinancialStateStore(), "test", "BTC-USDT");
    }

    public TradingStateRepository(
            Path statePath,
            TradingFinancialStateStore financialStateStore,
            String accountScope,
            String instId
    ) {
        this.statePath = statePath;
        this.financialStateStore = financialStateStore;
        this.accountScope = accountScope;
        this.instId = instId;
    }

    public synchronized TradingState getState() {
        TradingMemoryDocument current = memory();
        TradingState seed = legacySeed();
        TradingPositionState position = financialStateStore.getOrCreatePosition(
                accountScope,
                instId,
                seed.getTrackedBaseAmount(),
                seed.getAverageCost()
        );
        TradingRiskState riskState = financialStateStore.getOrCreateRiskState(
                accountScope,
                seed.getRiskState()
        );
        return compose(current, position, riskState);
    }

    public synchronized void recordBuy(BigDecimal baseAmount, BigDecimal price) {
        if (!positive(baseAmount) || !positive(price)) {
            return;
        }
        financialStateStore.recordBuy(accountScope, instId, baseAmount, price);
    }

    public synchronized void recordSell(BigDecimal baseAmount) {
        if (!positive(baseAmount)) {
            return;
        }
        financialStateStore.recordSell(accountScope, instId, baseAmount);
    }

    public synchronized TradingPositionState recordExchangePosition(
            BigDecimal exchangeQuantity,
            BigDecimal authoritativeQuantity,
            BigDecimal authoritativeAverageCost,
            Instant reconciledAt
    ) {
        return financialStateStore.recordExchangePosition(
                accountScope,
                instId,
                exchangeQuantity,
                authoritativeQuantity,
                authoritativeAverageCost,
                reconciledAt
        );
    }

    public SpotFillApplication applyCumulativeSpotFill(
            long orderId,
            String side,
            BigDecimal cumulativeFilledSize,
            BigDecimal cumulativePositionQuantity,
            BigDecimal cumulativeQuoteCost,
            BigDecimal averageFillPrice,
            BigDecimal fee,
            String feeCcy,
            String exchangeState,
            Instant exchangeUpdatedAt
    ) {
        return financialStateStore.applyCumulativeSpotFill(
                orderId,
                accountScope,
                instId,
                side,
                cumulativeFilledSize,
                cumulativePositionQuantity,
                cumulativeQuoteCost,
                averageFillPrice,
                fee,
                feeCcy,
                exchangeState,
                exchangeUpdatedAt
        );
    }

    public synchronized void recordDecision(TradingDecisionRecord record, int limit) {
        if (record == null || limit <= 0) {
            return;
        }
        TradingMemoryDocument current = memory();
        List<TradingDecisionRecord> recent = new ArrayList<>();
        recent.add(copyDecision(record));
        recent.addAll(copyRecentDecisions(current.getRecentDecisions()));
        if (recent.size() > limit) {
            recent = new ArrayList<>(recent.subList(0, limit));
        }
        current.setRecentDecisions(recent);
        writeMemory(current);
    }

    public synchronized void recordStrategyState(String decisionId, AiTradingDecision decision) {
        if (decision == null || !hasStrategyUpdate(decision)) {
            return;
        }
        TradingMemoryDocument current = memory();
        TradingStrategyState previous = current.getStrategyState();
        current.setStrategyState(new TradingStrategyState()
                .setBias(firstText(decision.getStrategyBias(), previous == null ? null : previous.getBias()))
                .setThesis(firstText(decision.getStrategyThesis(), previous == null ? null : previous.getThesis()))
                .setInvalidation(firstText(
                        decision.getStrategyInvalidation(),
                        previous == null ? null : previous.getInvalidation()
                ))
                .setHorizon(firstText(decision.getStrategyHorizon(), previous == null ? null : previous.getHorizon()))
                .setUpdatedAt(Instant.now().toString())
                .setSourceDecisionId(decisionId));
        writeMemory(current);
    }

    public synchronized void recordRiskState(TradingRiskState riskState) {
        if (riskState == null) {
            return;
        }
        // Ensure a legacy row is initialized before replacing it.
        getState();
        financialStateStore.saveRiskState(accountScope, copyRiskState(riskState));
    }

    public synchronized TradingRiskState recordReconciliationSuccess(Instant reconciledAt) {
        // Initialize a legacy risk baseline before using field-specific atomic
        // updates, otherwise an early recovery loop could create an empty row.
        getState();
        return financialStateStore.recordReconciliationSuccess(accountScope, reconciledAt);
    }

    public synchronized TradingRiskState recordReconciliationFailure(Instant reconciledAt, String error) {
        getState();
        return financialStateStore.recordReconciliationFailure(accountScope, reconciledAt, error);
    }

    /**
     * Atomically changes the strategy used by scheduled decisions.
     *
     * <p>The optional revision prevents two operator screens from silently
     * overwriting each other. Re-selecting the current strategy is idempotent
     * and does not advance the revision.</p>
     */
    public synchronized TradingState selectActiveStrategy(String strategyId, Long expectedRevision) {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId is required");
        }
        TradingMemoryDocument current = memory();
        if (expectedRevision != null && expectedRevision.longValue() != current.getActiveStrategyRevision()) {
            throw new ConcurrentModificationException(
                    "Active strategy revision changed from " + expectedRevision
                            + " to " + current.getActiveStrategyRevision()
            );
        }
        String normalized = strategyId.trim();
        if (!normalized.equals(current.getActiveStrategyId())) {
            current.setActiveStrategyId(normalized);
            current.setActiveStrategyRevision(current.getActiveStrategyRevision() + 1L);
            current.setActiveStrategyChangedAt(Instant.now().toString());
            writeMemory(current);
        }
        return getState();
    }

    private TradingMemoryDocument memory() {
        if (memory == null) {
            memory = readMemory();
        }
        return memory;
    }

    private TradingState legacySeed() {
        if (legacySeed != null) {
            return legacySeed;
        }
        if (!Files.exists(statePath)) {
            legacySeed = new TradingState();
            return legacySeed;
        }
        try {
            legacySeed = objectMapper.readValue(statePath.toFile(), TradingState.class);
        } catch (Exception e) {
            throw new IllegalStateException("Read legacy trading state failed: " + statePath, e);
        }
        if (legacySeed.getTrackedBaseAmount() == null) {
            legacySeed.setTrackedBaseAmount(BigDecimal.ZERO);
        }
        if (legacySeed.getAverageCost() == null) {
            legacySeed.setAverageCost(BigDecimal.ZERO);
        }
        if (legacySeed.getRiskState() == null) {
            legacySeed.setRiskState(new TradingRiskState());
        }
        return legacySeed;
    }

    private TradingMemoryDocument readMemory() {
        if (!Files.exists(statePath)) {
            return new TradingMemoryDocument();
        }
        try {
            TradingMemoryDocument loaded = objectMapper.readValue(statePath.toFile(), TradingMemoryDocument.class);
            if (loaded.getRecentDecisions() == null) {
                loaded.setRecentDecisions(new ArrayList<>());
            }
            if (loaded.getStrategyState() == null) {
                loaded.setStrategyState(new TradingStrategyState());
            }
            return loaded;
        } catch (Exception e) {
            throw new IllegalStateException("Read trading memory failed: " + statePath, e);
        }
    }

    private void writeMemory(TradingMemoryDocument nextMemory) {
        try {
            // Initialize legacy financial state before the old JSON values are
            // intentionally removed from the rewritten memory document.
            getState();
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(statePath.toFile(), nextMemory);
            memory = nextMemory;
        } catch (Exception e) {
            throw new IllegalStateException("Write trading memory failed: " + statePath, e);
        }
    }

    private static TradingState compose(
            TradingMemoryDocument memory,
            TradingPositionState position,
            TradingRiskState riskState
    ) {
        return new TradingState()
                .setTrackedBaseAmount(zero(position.getQuantity()))
                .setAverageCost(zero(position.getAverageCost()))
                .setExchangeBaseAmount(position.getExchangeQuantity())
                .setFinancialAccountScope(position.getAccountScope())
                .setPositionVersion(position.getVersion())
                .setPositionLastReconciledAt(position.getLastReconciledAt() == null
                        ? null
                        : position.getLastReconciledAt().toString())
                .setUpdatedAt(Instant.now().toString())
                .setActiveStrategyId(memory.getActiveStrategyId())
                .setActiveStrategyRevision(memory.getActiveStrategyRevision())
                .setActiveStrategyChangedAt(memory.getActiveStrategyChangedAt())
                .setStrategyState(copyStrategyState(memory.getStrategyState()))
                .setRiskState(copyRiskState(riskState))
                .setRecentDecisions(copyRecentDecisions(memory.getRecentDecisions()));
    }

    private static List<TradingDecisionRecord> copyRecentDecisions(List<TradingDecisionRecord> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<TradingDecisionRecord> copy = new ArrayList<>(source.size());
        for (TradingDecisionRecord record : source) {
            copy.add(copyDecision(record));
        }
        return copy;
    }

    private static TradingDecisionRecord copyDecision(TradingDecisionRecord source) {
        if (source == null) {
            return null;
        }
        return new TradingDecisionRecord()
                .setDecisionId(source.getDecisionId())
                .setStrategyId(source.getStrategyId())
                .setTimestamp(source.getTimestamp())
                .setTriggerType(source.getTriggerType())
                .setTriggerReason(source.getTriggerReason())
                .setAction(source.getAction())
                .setReason(source.getReason())
                .setBuyQuoteAmountUsdt(source.getBuyQuoteAmountUsdt())
                .setSellBaseAmountBtc(source.getSellBaseAmountBtc())
                .setRequestedOrderSize(source.getRequestedOrderSize())
                .setWinProbability(source.getWinProbability())
                .setConfidence(source.getConfidence())
                .setObjectiveAlignment(source.getObjectiveAlignment())
                .setExpectedNetEdgePercent(source.getExpectedNetEdgePercent())
                .setRiskRewardRatio(source.getRiskRewardRatio())
                .setThesisChangeEvidence(source.getThesisChangeEvidence())
                .setStrategyBias(source.getStrategyBias())
                .setStrategyThesis(source.getStrategyThesis())
                .setStrategyInvalidation(source.getStrategyInvalidation())
                .setStrategyHorizon(source.getStrategyHorizon())
                .setLastPrice(source.getLastPrice())
                .setAvailableBase(source.getAvailableBase())
                .setAvailableQuote(source.getAvailableQuote())
                .setExecutionStatus(source.getExecutionStatus())
                .setSkipReason(source.getSkipReason())
                .setOrderId(source.getOrderId())
                .setClientOrderId(source.getClientOrderId())
                .setIdempotencyKey(source.getIdempotencyKey())
                .setOrderStatus(source.getOrderStatus())
                .setOrderStatusVersion(source.getOrderStatusVersion())
                .setIdempotentReplay(source.isIdempotentReplay())
                .setOrderSize(source.getOrderSize())
                .setFilledBaseAmount(source.getFilledBaseAmount())
                .setAverageFillPrice(source.getAverageFillPrice())
                .setFee(source.getFee())
                .setFeeCcy(source.getFeeCcy())
                .setError(source.getError())
                .setMetadata(source.getMetadata());
    }

    private static TradingStrategyState copyStrategyState(TradingStrategyState source) {
        if (source == null) {
            return new TradingStrategyState();
        }
        return new TradingStrategyState()
                .setBias(source.getBias())
                .setThesis(source.getThesis())
                .setInvalidation(source.getInvalidation())
                .setHorizon(source.getHorizon())
                .setUpdatedAt(source.getUpdatedAt())
                .setSourceDecisionId(source.getSourceDecisionId());
    }

    private static TradingRiskState copyRiskState(TradingRiskState source) {
        if (source == null) {
            return new TradingRiskState();
        }
        return new TradingRiskState()
                .setCurrentEquity(zero(source.getCurrentEquity()))
                .setEquityHighWatermark(zero(source.getEquityHighWatermark()))
                .setDayStartEquity(zero(source.getDayStartEquity()))
                .setDayStartDate(source.getDayStartDate())
                .setConsecutiveLosses(source.getConsecutiveLosses())
                .setLossCooldownUntil(source.getLossCooldownUntil())
                .setLastTradeTime(source.getLastTradeTime())
                .setConsecutiveOpenActions(source.getConsecutiveOpenActions())
                .setLastRiskReason(source.getLastRiskReason())
                .setConsecutiveReconciliationFailures(source.getConsecutiveReconciliationFailures())
                .setLastReconciliationAt(source.getLastReconciliationAt())
                .setLastReconciliationError(source.getLastReconciliationError());
    }

    private static boolean hasStrategyUpdate(AiTradingDecision decision) {
        return hasText(decision.getStrategyBias())
                || hasText(decision.getStrategyThesis())
                || hasText(decision.getStrategyInvalidation())
                || hasText(decision.getStrategyHorizon());
    }

    private static String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Data
    private static class TradingMemoryDocument {
        private String activeStrategyId;
        private long activeStrategyRevision;
        private String activeStrategyChangedAt;
        private TradingStrategyState strategyState = new TradingStrategyState();
        private List<TradingDecisionRecord> recentDecisions = new ArrayList<>();
    }
}
