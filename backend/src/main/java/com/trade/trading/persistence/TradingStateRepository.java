package com.trade.trading.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.model.TradingState;
import com.trade.trading.model.TradingStrategyState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * File-backed local trading memory used by prompts, risk checks, and fill
 * reconciliation. All public mutations are synchronized because multiple
 * schedulers can read or update this state.
 */
@Component
public class TradingStateRepository {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final Path statePath;
    // Cached copy of the JSON file; public methods return deep copies so callers
    // cannot mutate the repository state without going through record* methods.
    private TradingState state;

    @Autowired
    public TradingStateRepository(TradingProperties properties) {
        this(Path.of(properties.getStateFile()));
    }

    public TradingStateRepository(Path statePath) {
        this.statePath = statePath;
    }

    public synchronized TradingState getState() {
        if (state == null) {
            state = readState();
        }
        return copy(state);
    }

    public synchronized void recordBuy(BigDecimal baseAmount, BigDecimal price) {
        if (baseAmount == null || price == null || baseAmount.signum() <= 0 || price.signum() <= 0) {
            return;
        }

        TradingState current = state == null ? readState() : state;
        BigDecimal oldBase = nullToZero(current.getTrackedBaseAmount());
        BigDecimal oldCost = nullToZero(current.getAverageCost());
        BigDecimal newBase = oldBase.add(baseAmount);
        // Weighted-average cost basis after fees, used later by the prompt to
        // estimate whether a sell is actually profitable.
        BigDecimal newCost = oldBase.multiply(oldCost)
                .add(baseAmount.multiply(price))
                .divide(newBase, 18, java.math.RoundingMode.HALF_UP);

        state = new TradingState()
                .setTrackedBaseAmount(newBase)
                .setAverageCost(newCost)
                .setUpdatedAt(Instant.now().toString())
                .setStrategyState(copyStrategyState(current.getStrategyState()))
                .setRiskState(copyRiskState(current.getRiskState()))
                .setRecentDecisions(copyRecentDecisions(current.getRecentDecisions()));
        writeState(state);
    }

    public synchronized void recordSell(BigDecimal baseAmount) {
        if (baseAmount == null || baseAmount.signum() <= 0) {
            return;
        }

        TradingState current = state == null ? readState() : state;
        BigDecimal oldBase = nullToZero(current.getTrackedBaseAmount());
        BigDecimal remaining = oldBase.subtract(baseAmount);
        if (remaining.signum() <= 0) {
            remaining = BigDecimal.ZERO;
        }

        BigDecimal averageCost = remaining.signum() > 0
                ? nullToZero(current.getAverageCost())
                : BigDecimal.ZERO;
        state = new TradingState()
                .setTrackedBaseAmount(remaining)
                .setAverageCost(averageCost)
                .setUpdatedAt(Instant.now().toString())
                .setStrategyState(copyStrategyState(current.getStrategyState()))
                .setRiskState(copyRiskState(current.getRiskState()))
                .setRecentDecisions(copyRecentDecisions(current.getRecentDecisions()));
        writeState(state);
    }

    public synchronized void recordDecision(TradingDecisionRecord record, int limit) {
        if (record == null || limit <= 0) {
            return;
        }

        // Newest-first list gives the AI short-term memory without making the
        // prompt grow indefinitely.
        TradingState current = state == null ? readState() : state;
        List<TradingDecisionRecord> recent = new ArrayList<>();
        recent.add(copyDecision(record));
        recent.addAll(copyRecentDecisions(current.getRecentDecisions()));
        if (recent.size() > limit) {
            recent = new ArrayList<>(recent.subList(0, limit));
        }

        state = new TradingState()
                .setTrackedBaseAmount(nullToZero(current.getTrackedBaseAmount()))
                .setAverageCost(nullToZero(current.getAverageCost()))
                .setUpdatedAt(current.getUpdatedAt())
                .setStrategyState(copyStrategyState(current.getStrategyState()))
                .setRiskState(copyRiskState(current.getRiskState()))
                .setRecentDecisions(recent);
        writeState(state);
    }

    public synchronized void recordStrategyState(String decisionId, AiTradingDecision decision) {
        if (decision == null || !hasStrategyUpdate(decision)) {
            return;
        }

        TradingState current = state == null ? readState() : state;
        TradingStrategyState previous = current.getStrategyState();
        TradingStrategyState nextStrategyState = new TradingStrategyState()
                .setBias(firstText(decision.getStrategyBias(), previous == null ? null : previous.getBias()))
                .setThesis(firstText(decision.getStrategyThesis(), previous == null ? null : previous.getThesis()))
                .setInvalidation(firstText(decision.getStrategyInvalidation(), previous == null ? null : previous.getInvalidation()))
                .setHorizon(firstText(decision.getStrategyHorizon(), previous == null ? null : previous.getHorizon()))
                .setUpdatedAt(Instant.now().toString())
                .setSourceDecisionId(decisionId);

        state = new TradingState()
                .setTrackedBaseAmount(nullToZero(current.getTrackedBaseAmount()))
                .setAverageCost(nullToZero(current.getAverageCost()))
                .setUpdatedAt(current.getUpdatedAt())
                .setStrategyState(nextStrategyState)
                .setRiskState(copyRiskState(current.getRiskState()))
                .setRecentDecisions(copyRecentDecisions(current.getRecentDecisions()));
        writeState(state);
    }

    public synchronized void recordRiskState(TradingRiskState riskState) {
        if (riskState == null) {
            return;
        }

        TradingState current = state == null ? readState() : state;
        state = new TradingState()
                .setTrackedBaseAmount(nullToZero(current.getTrackedBaseAmount()))
                .setAverageCost(nullToZero(current.getAverageCost()))
                .setUpdatedAt(current.getUpdatedAt())
                .setStrategyState(copyStrategyState(current.getStrategyState()))
                .setRiskState(copyRiskState(riskState))
                .setRecentDecisions(copyRecentDecisions(current.getRecentDecisions()));
        writeState(state);
    }

    private TradingState readState() {
        if (!Files.exists(statePath)) {
            return new TradingState().setUpdatedAt(Instant.now().toString());
        }

        try {
            TradingState loaded = objectMapper.readValue(statePath.toFile(), TradingState.class);
            if (loaded.getTrackedBaseAmount() == null) {
                loaded.setTrackedBaseAmount(BigDecimal.ZERO);
            }
            if (loaded.getAverageCost() == null) {
                loaded.setAverageCost(BigDecimal.ZERO);
            }
            if (loaded.getRecentDecisions() == null) {
                loaded.setRecentDecisions(new ArrayList<>());
            }
            if (loaded.getStrategyState() == null) {
                loaded.setStrategyState(new TradingStrategyState());
            }
            if (loaded.getRiskState() == null) {
                loaded.setRiskState(new TradingRiskState());
            }
            return loaded;
        } catch (Exception e) {
            throw new IllegalStateException("Read trading state failed: " + statePath, e);
        }
    }

    private void writeState(TradingState nextState) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(statePath.toFile(), nextState);
        } catch (Exception e) {
            throw new IllegalStateException("Write trading state failed: " + statePath, e);
        }
    }

    private static TradingState copy(TradingState source) {
        return new TradingState()
                .setTrackedBaseAmount(nullToZero(source.getTrackedBaseAmount()))
                .setAverageCost(nullToZero(source.getAverageCost()))
                .setUpdatedAt(source.getUpdatedAt())
                .setStrategyState(copyStrategyState(source.getStrategyState()))
                .setRiskState(copyRiskState(source.getRiskState()))
                .setRecentDecisions(copyRecentDecisions(source.getRecentDecisions()));
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
                .setCurrentEquity(nullToZero(source.getCurrentEquity()))
                .setEquityHighWatermark(nullToZero(source.getEquityHighWatermark()))
                .setDayStartEquity(nullToZero(source.getDayStartEquity()))
                .setDayStartDate(source.getDayStartDate())
                .setConsecutiveLosses(source.getConsecutiveLosses())
                .setLossCooldownUntil(source.getLossCooldownUntil())
                .setLastTradeTime(source.getLastTradeTime())
                .setConsecutiveOpenActions(source.getConsecutiveOpenActions())
                .setLastRiskReason(source.getLastRiskReason());
    }

    private static boolean hasStrategyUpdate(AiTradingDecision decision) {
        return hasText(decision.getStrategyBias())
                || hasText(decision.getStrategyThesis())
                || hasText(decision.getStrategyInvalidation())
                || hasText(decision.getStrategyHorizon());
    }

    private static String firstText(String first, String second) {
        if (hasText(first)) {
            return first;
        }
        return second;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
