package com.trade.trading.application;

import com.trade.ai.persistence.AiResponseParseErrorRecord;
import com.trade.ai.persistence.AiResponseParseErrorSink;
import com.trade.client.ai.AiResponseParseException;
import com.trade.client.ai.AiTextClient;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.decision.AiPromptBuilder;
import com.trade.trading.decision.AiTradingDecisionParser;
import com.trade.trading.execution.TradingOrderExecutor;
import com.trade.trading.market.MarketContextCollector;
import com.trade.trading.model.AiDecisionAuditRecord;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.persistence.AiDecisionAuditSink;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.support.TradingMath;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates one OKX AI trading decision from market snapshot to execution.
 *
 * <p>The service is intentionally sequential: a decision uses account balances,
 * open orders, local state, and risk state that should not be evaluated by two
 * overlapping AI calls.</p>
 */
@Component
public class AiTradingService {
    private static final Logger log = LoggerFactory.getLogger(AiTradingService.class);

    private final AiTextClient aiTextClient;
    private final AiTradingDecisionParser decisionParser;
    private final MarketContextCollector contextCollector;
    private final AiPromptBuilder promptBuilder;
    private final TradingOrderExecutor orderExecutor;
    private final TradingStateRepository stateRepository;
    private final AiDecisionAuditSink auditSink;
    private final AiResponseParseErrorSink parseErrorSink;
    private final TradingProperties properties;
    // Prevents concurrent decisions from using the same account snapshot and
    // then racing each other into duplicate orders.
    private final ReentrantLock decisionLock = new ReentrantLock();

    public AiTradingService(
            AiTextClient aiTextClient,
            AiTradingDecisionParser decisionParser,
            MarketContextCollector contextCollector,
            AiPromptBuilder promptBuilder,
            TradingOrderExecutor orderExecutor,
            TradingStateRepository stateRepository,
            AiDecisionAuditSink auditSink,
            TradingProperties properties
    ) {
        this(
                aiTextClient,
                decisionParser,
                contextCollector,
                promptBuilder,
                orderExecutor,
                stateRepository,
                auditSink,
                properties,
                AiResponseParseErrorSink.NOOP
        );
    }

    @Autowired
    public AiTradingService(
            AiTextClient aiTextClient,
            AiTradingDecisionParser decisionParser,
            MarketContextCollector contextCollector,
            AiPromptBuilder promptBuilder,
            TradingOrderExecutor orderExecutor,
            TradingStateRepository stateRepository,
            AiDecisionAuditSink auditSink,
            TradingProperties properties,
            AiResponseParseErrorSink parseErrorSink
    ) {
        this.aiTextClient = aiTextClient;
        this.decisionParser = decisionParser;
        this.contextCollector = contextCollector;
        this.promptBuilder = promptBuilder;
        this.orderExecutor = orderExecutor;
        this.stateRepository = stateRepository;
        this.auditSink = auditSink;
        this.properties = properties;
        this.parseErrorSink = parseErrorSink == null ? AiResponseParseErrorSink.NOOP : parseErrorSink;
    }

    public boolean runDecision(TradingTrigger trigger) {
        if (!properties.isEnabled()) {
            log.info("AI trading is disabled, skip trigger={}", trigger);
            return false;
        }
        if (!decisionLock.tryLock()) {
            log.info("AI decision is already running, skip trigger={}", trigger);
            return false;
        }

        Instant startedAt = Instant.now();
        Long decisionId = persistAuditStart(startedAt, trigger);
        TradingDecisionContext context = null;
        String prompt = null;
        String rawAiResponse = null;
        AiTradingDecision decision = null;
        TradingDecisionRecord decisionRecord = null;
        Exception failure = null;

        try {
            // 1. Collect all market/account/local state that the prompt and
            // executor must agree on.
            context = contextCollector.collect(trigger);
            log.info("AI decision parameters:\n {}", context.getAiParametersJson());

            prompt = promptBuilder.buildPrompt(context.getAiParametersJson());

            try {
                // 2. Ask the configured AI client for strict JSON. Client-level
                // parse failures are separately audited with the raw response.
                rawAiResponse = aiTextClient.generateJson(prompt);
            } catch (AiResponseParseException e) {
                rawAiResponse = e.getRawResponse();
                persistAiResponseParseError(
                        decisionId,
                        "AI_CLIENT_RESPONSE",
                        prompt,
                        rawAiResponse,
                        e.getMessage(),
                        null
                );
                throw e;
            }
            log.info("AI raw decision response:\n {}", rawAiResponse);

            // 3. Convert invalid model payloads into HOLD decisions so the
            // executor can stay defensive and deterministic.
            decision = decisionParser.parse(rawAiResponse);
            if (isInvalidAiDecision(decision)) {
                persistAiResponseParseError(
                        decisionId,
                        "DECISION_PAYLOAD",
                        prompt,
                        rawAiResponse,
                        decision.getReason(),
                        decision.getAction().name()
                );
            }
            log.info(
                    "AI parsed decision: action={}, reason={}, buyQuoteAmountUsdt={}, sellBaseAmountBtc={}, orderSize={}, winProbability={}, confidence={}, objectiveAlignment={}, expectedNetEdgePercent={}, riskRewardRatio={}, strategyBias={}",
                    decision.getAction(),
                    decision.getReason(),
                    decision.getBuyQuoteAmountUsdt(),
                    decision.getSellBaseAmountBtc(),
                    decision.getOrderSize(),
                    decision.getWinProbability(),
                    decision.getConfidence(),
                    decision.getObjectiveAlignment(),
                    decision.getExpectedNetEdgePercent(),
                    decision.getRiskRewardRatio(),
                    decision.getStrategyBias()
            );

            decisionRecord = decisionRecord(decisionId, startedAt, trigger, decision, context);
            try {
                // 4. Risk checks and order placement happen in the executor,
                // but still use the exact context that was shown to the model.
                orderExecutor.execute(decision, context, decisionRecord);
            } catch (Exception e) {
                decisionRecord.setExecutionStatus("FAILED")
                        .setError(e.getMessage());
                throw e;
            }
            return true;
        } catch (Exception e) {
            failure = e;
            log.error("AI trading decision failed, trigger={}, err={}", trigger, e.getMessage(), e);
            return false;
        } finally {
            // Persist every available artifact even after partial failures, so
            // a failed run can still be debugged from audit tables/local state.
            AiDecisionAuditRecord auditRecord = new AiDecisionAuditRecord()
                    .setDecisionId(decisionId)
                    .setStartedAt(startedAt)
                    .setCompletedAt(Instant.now())
                    .setTrigger(trigger)
                    .setContext(context)
                    .setPrompt(prompt)
                    .setRawAiResponse(rawAiResponse)
                    .setAiDecision(decision)
                    .setDecisionRecord(decisionRecord)
                    .setError(failure == null ? null : failure.getMessage());
            persistAuditRecord(auditRecord);
            decisionId = auditRecord.getDecisionId();
            if (decisionRecord != null && decisionRecord.getDecisionId() == null) {
                decisionRecord.setDecisionId(localDecisionId(decisionId));
            }
            persistDecisionRecord(decisionRecord);
            if (failure == null) {
                persistStrategyState(decisionId, decision);
            }
            decisionLock.unlock();
        }
    }

    private TradingDecisionRecord decisionRecord(
            Long decisionId,
            Instant timestamp,
            TradingTrigger trigger,
            AiTradingDecision decision,
            TradingDecisionContext context
    ) {
        return new TradingDecisionRecord()
                .setDecisionId(localDecisionId(decisionId))
                .setTimestamp(timestamp.toString())
                .setTriggerType(trigger == null ? null : trigger.type())
                .setTriggerReason(trigger == null ? null : trigger.reason())
                .setAction(decision.getAction())
                .setReason(decision.getReason())
                .setBuyQuoteAmountUsdt(decision.getBuyQuoteAmountUsdt())
                .setSellBaseAmountBtc(decision.getSellBaseAmountBtc())
                .setRequestedOrderSize(decision.getOrderSize())
                .setWinProbability(decision.getWinProbability())
                .setConfidence(decision.getConfidence())
                .setObjectiveAlignment(decision.getObjectiveAlignment())
                .setExpectedNetEdgePercent(decision.getExpectedNetEdgePercent())
                .setRiskRewardRatio(decision.getRiskRewardRatio())
                .setThesisChangeEvidence(decision.getThesisChangeEvidence())
                .setStrategyBias(decision.getStrategyBias())
                .setStrategyThesis(decision.getStrategyThesis())
                .setStrategyInvalidation(decision.getStrategyInvalidation())
                .setStrategyHorizon(decision.getStrategyHorizon())
                .setLastPrice(lastPrice(context))
                .setAvailableBase(available(context.getBaseBalance()))
                .setAvailableQuote(available(context.getQuoteBalance()))
                .setExecutionStatus("PARSED");
    }

    private void persistDecisionRecord(TradingDecisionRecord decisionRecord) {
        if (decisionRecord == null) {
            return;
        }
        try {
            stateRepository.recordDecision(decisionRecord, properties.getRecentDecisionMemoryLimit());
        } catch (Exception e) {
            log.warn("Persist AI decision record failed: {}", e.getMessage(), e);
        }
    }

    private Long persistAuditStart(Instant startedAt, TradingTrigger trigger) {
        try {
            return auditSink.start(new AiDecisionAuditRecord()
                    .setStartedAt(startedAt)
                    .setTrigger(trigger));
        } catch (Exception e) {
            log.warn("Start AI decision audit record failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private void persistStrategyState(Long decisionId, AiTradingDecision decision) {
        try {
            stateRepository.recordStrategyState(localDecisionId(decisionId), decision);
        } catch (Exception e) {
            log.warn("Persist AI strategy state failed: {}", e.getMessage(), e);
        }
    }

    private void persistAuditRecord(AiDecisionAuditRecord auditRecord) {
        try {
            auditSink.save(auditRecord);
        } catch (Exception e) {
            log.warn("Persist AI decision audit record failed: {}", e.getMessage(), e);
        }
    }

    private void persistAiResponseParseError(
            Long decisionId,
            String phase,
            String prompt,
            String rawAiResponse,
            String errorMessage,
            String fallbackAction
    ) {
        try {
            parseErrorSink.save(new AiResponseParseErrorRecord()
                    .setSource("OKX_TRADING")
                    .setPhase(phase)
                    .setRelatedId(localDecisionId(decisionId))
                    .setPromptText(prompt)
                    .setRawResponse(rawAiResponse)
                    .setErrorMessage(errorMessage)
                    .setFallbackAction(fallbackAction));
        } catch (Exception e) {
            log.warn("Persist AI response parse error failed: {}", e.getMessage(), e);
        }
    }

    private static boolean isInvalidAiDecision(AiTradingDecision decision) {
        return decision != null
                && decision.getAction() != null
                && "HOLD".equals(decision.getAction().name())
                && decision.getReason() != null
                && decision.getReason().startsWith("Invalid AI decision:");
    }

    private static String localDecisionId(Long decisionId) {
        return decisionId == null ? null : decisionId.toString();
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
