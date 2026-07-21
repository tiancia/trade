package com.trade.trading.application;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.execution.TradingBroker;
import com.trade.trading.market.MarketContextCollector;
import com.trade.trading.market.OkxMarketDataWebSocketFeed;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.strategy.ConfiguredTradingStrategy;
import com.trade.trading.strategy.StrategyEvaluationContext;
import com.trade.common.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates one OKX strategy-decision run.
 *
 * <p>For onboarding, read this after {@link com.trade.trading.scheduler.TradingScheduler}:
 * it collects market context once, evaluates every configured strategy, routes
 * executable decisions to the selected broker, and records the result in local
 * trading state.</p>
 */
@Component
public class TradingStrategyEngine {
    private static final Logger log = LoggerFactory.getLogger(TradingStrategyEngine.class);

    private final MarketContextCollector contextCollector;
    private final TradingStrategySelectionService strategySelectionService;
    private final TradingBroker broker;
    private final TradingStateRepository stateRepository;
    private final TradingProperties properties;
    private final OkxMarketDataWebSocketFeed marketDataWebSocketFeed;
    private final ReentrantLock decisionLock = new ReentrantLock();
    private volatile TradingDecisionRecord lastDecision;
    private volatile String lastError;
    private volatile Instant lastRunStartedAt;
    private volatile Instant lastRunCompletedAt;

    public TradingStrategyEngine(
            MarketContextCollector contextCollector,
            TradingStrategySelectionService strategySelectionService,
            @Qualifier("tradingBrokerRouter") TradingBroker broker,
            TradingStateRepository stateRepository,
            TradingProperties properties,
            OkxMarketDataWebSocketFeed marketDataWebSocketFeed
    ) {
        this.contextCollector = contextCollector;
        this.strategySelectionService = strategySelectionService;
        this.broker = broker;
        this.stateRepository = stateRepository;
        this.properties = properties;
        this.marketDataWebSocketFeed = marketDataWebSocketFeed;
    }

    public boolean runDecision(TradingTrigger trigger) {
        if (!properties.isEnabled()) {
            log.info("OKX strategy trading is disabled, skip trigger={}", trigger);
            return false;
        }
        if (!decisionLock.tryLock()) {
            log.info("Strategy decision is already running, skip trigger={}", trigger);
            return false;
        }

        lastRunStartedAt = Instant.now();
        lastError = null;
        try {
            TradingDecisionContext marketContext = contextCollector.collect(trigger);
            TradingDecisionRecord finalRecord = null;
            for (ConfiguredTradingStrategy<?> configured : strategySelectionService.activeStrategies()) {
                StrategyDecision decision = evaluate(configured, trigger, marketContext);
                TradingDecisionRecord record = decisionRecord(decision, trigger, marketContext);
                finalRecord = record;
                if (decision == null || decision.isHold()) {
                    record.setExecutionStatus("HELD");
                    continue;
                }

                try {
                    broker.execute(decision, marketContext, record);
                } catch (Exception e) {
                    if (record.getOrderStatus() == null) {
                        record.setExecutionStatus("FAILED");
                    }
                    record.setError(e.getMessage());
                    throw e;
                } finally {
                    persistDecisionRecord(record);
                    lastDecision = record;
                }
                return true;
            }

            if (finalRecord != null) {
                persistDecisionRecord(finalRecord);
                lastDecision = finalRecord;
            }
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("OKX strategy decision failed, trigger={}, err={}", trigger, e.getMessage(), e);
            return false;
        } finally {
            lastRunCompletedAt = Instant.now();
            decisionLock.unlock();
        }
    }

    public TradingRuntimeStatus status() {
        ActiveStrategySelection selection;
        List<String> strategyIds;
        try {
            selection = strategySelectionService.current();
            strategyIds = selection.strategyId() == null
                    ? List.of()
                    : List.of(selection.strategyId());
        } catch (Exception e) {
            selection = new ActiveStrategySelection(null, 0L, null);
            strategyIds = List.of();
        }
        return new TradingRuntimeStatus()
                .setExecutionMode(properties.getExecutionMode())
                .setLiveEnabled(properties.isLiveEnabled())
                .setRunningStrategyIds(strategyIds)
                .setActiveStrategyId(selection.strategyId())
                .setActiveStrategyRevision(selection.revision())
                .setActiveStrategyChangedAt(selection.changedAt())
                .setLastDecision(lastDecision)
                .setLastError(lastError)
                .setLastRunStartedAt(lastRunStartedAt)
                .setLastRunCompletedAt(lastRunCompletedAt)
                .setMarketDataStale(marketDataWebSocketFeed.latestTicker().isEmpty()
                        || marketDataWebSocketFeed.recentOneMinuteCandles(1).isEmpty());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private StrategyDecision evaluate(
            ConfiguredTradingStrategy configured,
            TradingTrigger trigger,
            TradingDecisionContext marketContext
    ) {
        StrategyEvaluationContext evaluationContext = new StrategyEvaluationContext()
                .setStrategyId(configured.id())
                .setBar(configured.bar())
                .setTrigger(trigger)
                .setMarketContext(marketContext)
                .setProperties(properties)
                .setEvaluatedAt(Instant.now());
        try {
            StrategyDecision decision = configured.strategy().evaluate(evaluationContext, configured.config());
            if (decision == null) {
                return StrategyDecision.hold(configured.id(), "Strategy returned null decision");
            }
            if (decision.getStrategyId() == null || decision.getStrategyId().isBlank()) {
                decision.setStrategyId(configured.id());
            }
            return decision;
        } catch (Exception e) {
            lastError = "Strategy " + configured.id() + " failed: " + e.getMessage();
            log.warn("Strategy evaluation failed: strategyId={}", configured.id(), e);
            return StrategyDecision.hold(configured.id(), lastError);
        }
    }

    private TradingDecisionRecord decisionRecord(
            StrategyDecision decision,
            TradingTrigger trigger,
            TradingDecisionContext context
    ) {
        StrategyDecision safeDecision = decision == null
                ? StrategyDecision.hold(null, "Strategy returned null decision")
                : decision;
        return new TradingDecisionRecord()
                .setDecisionId(UUID.randomUUID().toString())
                .setStrategyId(safeDecision.getStrategyId())
                .setTimestamp(Instant.now().toString())
                .setTriggerType(trigger == null ? null : trigger.type())
                .setTriggerReason(trigger == null ? null : trigger.reason())
                .setAction(safeDecision.getAction())
                .setReason(safeDecision.getReason())
                .setBuyQuoteAmountUsdt(safeDecision.getBuyQuoteAmount())
                .setSellBaseAmountBtc(safeDecision.getSellBaseAmount())
                .setRequestedOrderSize(safeDecision.getOrderSize())
                .setLastPrice(lastPrice(context))
                .setAvailableBase(available(context == null ? null : context.getBaseBalance()))
                .setAvailableQuote(available(context == null ? null : context.getQuoteBalance()))
                .setExecutionStatus("EVALUATED")
                .setMetadata(safeDecision.getMetadata());
    }

    private void persistDecisionRecord(TradingDecisionRecord decisionRecord) {
        try {
            stateRepository.recordDecision(decisionRecord, properties.getRecentDecisionMemoryLimit());
        } catch (Exception e) {
            log.warn("Persist strategy decision record failed: {}", e.getMessage(), e);
        }
    }

    private static BigDecimal available(BalanceDetail detail) {
        if (detail == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal availBal = TradingMath.decimal(detail.getAvailBal());
        if (availBal.signum() > 0) {
            return availBal;
        }
        return TradingMath.decimal(detail.getCashBal());
    }

    private static BigDecimal lastPrice(TradingDecisionContext context) {
        if (context == null || context.getTicker() == null) {
            return BigDecimal.ZERO;
        }
        return TradingMath.decimal(context.getTicker().getLast());
    }
}
