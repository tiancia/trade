package com.trade.trading.application;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

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
    private final TradingProperties properties;
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
        this.aiTextClient = aiTextClient;
        this.decisionParser = decisionParser;
        this.contextCollector = contextCollector;
        this.promptBuilder = promptBuilder;
        this.orderExecutor = orderExecutor;
        this.stateRepository = stateRepository;
        this.auditSink = auditSink;
        this.properties = properties;
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

        UUID decisionId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        TradingDecisionContext context = null;
        String prompt = null;
        String rawAiResponse = null;
        AiTradingDecision decision = null;
        TradingDecisionRecord decisionRecord = null;
        Exception failure = null;

        try {
            context = contextCollector.collect(trigger);
            log.info("AI decision parameters:\n {}", context.getAiParametersJson());

            prompt = promptBuilder.buildPrompt(context.getAiParametersJson());

            rawAiResponse = aiTextClient.generateJson(prompt);
            log.info("AI raw decision response:\n {}", rawAiResponse);

            decision = decisionParser.parse(rawAiResponse);
            log.info(
                    "AI parsed decision: action={}, reason={}, buyQuoteAmountUsdt={}, sellBaseAmountBtc={}",
                    decision.getAction(),
                    decision.getReason(),
                    decision.getBuyQuoteAmountUsdt(),
                    decision.getSellBaseAmountBtc()
            );

            decisionRecord = decisionRecord(decisionId, startedAt, trigger, decision, context);
            try {
                orderExecutor.execute(decision, context, decisionRecord);
                persistDecisionRecord(decisionRecord);
            } catch (Exception e) {
                decisionRecord.setExecutionStatus("FAILED")
                        .setError(e.getMessage());
                persistDecisionRecord(decisionRecord);
                throw e;
            }
            return true;
        } catch (Exception e) {
            failure = e;
            log.error("AI trading decision failed, trigger={}, err={}", trigger, e.getMessage(), e);
            return false;
        } finally {
            persistAuditRecord(
                    decisionId,
                    startedAt,
                    Instant.now(),
                    trigger,
                    context,
                    prompt,
                    rawAiResponse,
                    decision,
                    decisionRecord,
                    failure
            );
            decisionLock.unlock();
        }
    }

    private TradingDecisionRecord decisionRecord(
            UUID decisionId,
            Instant timestamp,
            TradingTrigger trigger,
            AiTradingDecision decision,
            TradingDecisionContext context
    ) {
        return new TradingDecisionRecord()
                .setDecisionId(decisionId)
                .setTimestamp(timestamp.toString())
                .setTriggerType(trigger == null ? null : trigger.type())
                .setTriggerReason(trigger == null ? null : trigger.reason())
                .setAction(decision.getAction())
                .setReason(decision.getReason())
                .setBuyQuoteAmountUsdt(decision.getBuyQuoteAmountUsdt())
                .setSellBaseAmountBtc(decision.getSellBaseAmountBtc())
                .setLastPrice(lastPrice(context))
                .setAvailableBase(available(context.getBaseBalance()))
                .setAvailableQuote(available(context.getQuoteBalance()))
                .setExecutionStatus("PARSED");
    }

    private void persistDecisionRecord(TradingDecisionRecord decisionRecord) {
        try {
            stateRepository.recordDecision(decisionRecord, properties.getRecentDecisionMemoryLimit());
        } catch (Exception e) {
            log.warn("Persist AI decision record failed: {}", e.getMessage(), e);
        }
    }

    private void persistAuditRecord(
            UUID decisionId,
            Instant startedAt,
            Instant completedAt,
            TradingTrigger trigger,
            TradingDecisionContext context,
            String prompt,
            String rawAiResponse,
            AiTradingDecision decision,
            TradingDecisionRecord decisionRecord,
            Exception failure
    ) {
        try {
            auditSink.save(new AiDecisionAuditRecord()
                    .setDecisionId(decisionId)
                    .setStartedAt(startedAt)
                    .setCompletedAt(completedAt)
                    .setTrigger(trigger)
                    .setContext(context)
                    .setPrompt(prompt)
                    .setRawAiResponse(rawAiResponse)
                    .setAiDecision(decision)
                    .setDecisionRecord(decisionRecord)
                    .setError(failure == null ? null : failure.getMessage()));
        } catch (Exception e) {
            log.warn("Persist AI decision audit record failed: {}", e.getMessage(), e);
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
