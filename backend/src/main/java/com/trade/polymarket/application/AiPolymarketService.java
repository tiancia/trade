package com.trade.polymarket.application;

import com.trade.ai.audit.AiResponseParseErrorRecord;
import com.trade.ai.audit.AiResponseParseErrorSink;
import com.trade.client.ai.AiResponseParseException;
import com.trade.client.ai.AiTextClient;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.decision.AiPolymarketDecisionParser;
import com.trade.polymarket.decision.AiPolymarketPromptBuilder;
import com.trade.polymarket.execution.PolymarketOrderExecutor;
import com.trade.polymarket.market.PolymarketMarketContextCollector;
import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketDecisionAuditRecord;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketOrderResult;
import com.trade.polymarket.persistence.PolymarketDecisionAuditSink;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs one Polymarket AI decision cycle: collect markets, prompt the model,
 * parse the decision, optionally place an order, and persist an audit record.
 */
@Component
public class AiPolymarketService {
    private static final Logger log = LoggerFactory.getLogger(AiPolymarketService.class);

    private final AiTextClient aiTextClient;
    private final PolymarketMarketContextCollector contextCollector;
    private final AiPolymarketPromptBuilder promptBuilder;
    private final AiPolymarketDecisionParser decisionParser;
    private final PolymarketOrderExecutor orderExecutor;
    private final AiPolymarketProperties properties;
    private final PolymarketDecisionAuditSink auditSink;
    private final AiResponseParseErrorSink parseErrorSink;
    // Market discovery and execution share mutable cursors/risk limits, so run
    // only one decision loop at a time.
    private final ReentrantLock decisionLock = new ReentrantLock();

    public AiPolymarketService(
            AiTextClient aiTextClient,
            PolymarketMarketContextCollector contextCollector,
            AiPolymarketPromptBuilder promptBuilder,
            AiPolymarketDecisionParser decisionParser,
            PolymarketOrderExecutor orderExecutor,
            AiPolymarketProperties properties,
            PolymarketDecisionAuditSink auditSink
    ) {
        this(
                aiTextClient,
                contextCollector,
                promptBuilder,
                decisionParser,
                orderExecutor,
                properties,
                auditSink,
                AiResponseParseErrorSink.NOOP
        );
    }

    @Autowired
    public AiPolymarketService(
            AiTextClient aiTextClient,
            PolymarketMarketContextCollector contextCollector,
            AiPolymarketPromptBuilder promptBuilder,
            AiPolymarketDecisionParser decisionParser,
            PolymarketOrderExecutor orderExecutor,
            AiPolymarketProperties properties,
            PolymarketDecisionAuditSink auditSink,
            AiResponseParseErrorSink parseErrorSink
    ) {
        this.aiTextClient = aiTextClient;
        this.contextCollector = contextCollector;
        this.promptBuilder = promptBuilder;
        this.decisionParser = decisionParser;
        this.orderExecutor = orderExecutor;
        this.properties = properties;
        this.auditSink = auditSink;
        this.parseErrorSink = parseErrorSink == null ? AiResponseParseErrorSink.NOOP : parseErrorSink;
    }

    public boolean runDecision() {
        if (!properties.isEnabled()) {
            log.info(
                    "AI Polymarket module is disabled: enabled={}, executionEnabled={}, fixedDelayMs={}, initialDelayMs={}",
                    properties.isEnabled(),
                    properties.getExecution().isEnabled(),
                    properties.getDecisionFixedDelayMs(),
                    properties.getInitialDelayMs()
            );
            return false;
        }
        if (!decisionLock.tryLock()) {
            log.info("AI Polymarket decision is already running");
            return false;
        }

        Instant startedAt = Instant.now();
        Long decisionId = persistAuditStart(startedAt);
        long startedAtMillis = System.currentTimeMillis();
        PolymarketDecisionContext context = null;
        String prompt = null;
        String rawAiResponse = null;
        AiPolymarketDecision decision = null;
        PolymarketOrderResult result = null;
        String error = null;
        try {
            log.info(
                    "AI Polymarket decision started: decisionId={}, executionEnabled={}, marketLimit={}, marketSlugs={}, marketIds={}, clobTokenIds={}",
                    decisionId,
                    properties.getExecution().isEnabled(),
                    properties.getMarketLimit(),
                    properties.getMarketSlugs(),
                    properties.getMarketIds(),
                    properties.getClobTokenIds()
            );
            // The collector returns both typed snapshots and the exact JSON
            // parameters used for the prompt/audit trail.
            context = contextCollector.collect();
            if (context.getMarkets() == null || context.getMarkets().isEmpty()) {
                log.info("AI Polymarket decision skipped: decisionId={}, no eligible markets collected", decisionId);
                result = PolymarketOrderResult.skipped("NO_ELIGIBLE_MARKETS");
                return false;
            }
            int outcomeCount = context.getMarkets().stream()
                    .mapToInt(market -> market.getOutcomes() == null ? 0 : market.getOutcomes().size())
                    .sum();
            log.info(
                    "AI Polymarket context collected: decisionId={}, marketCount={}, outcomeCount={}",
                    decisionId,
                    context.getMarkets().size(),
                    outcomeCount
            );
            log.info("AI Polymarket parameters: decisionId={}\n {}", decisionId, context.getAiParametersJson());

            prompt = promptBuilder.buildPrompt(context.getAiParametersJson());
            log.info("AI Polymarket request started: decisionId={}, promptChars={}", decisionId, prompt.length());
            try {
                // Client-level JSON extraction failures are persisted with the
                // raw response before being rethrown.
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
            log.info("AI Polymarket raw decision response: decisionId={}\n {}", decisionId, rawAiResponse);

            // Invalid BUY payloads become HOLD decisions, then are audited as
            // parse errors so no malformed order reaches the executor.
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
                    "AI Polymarket parsed decision: decisionId={}, action={}, marketSlug={}, outcome={}, tokenId={}, price={}, spendUsdc={}, winProbability={}, confidence={}, estimatedEdge={}, reason={}",
                    decisionId,
                    decision.getAction(),
                    decision.getMarketSlug(),
                    decision.getOutcome(),
                    decision.getTokenId(),
                    decision.getLimitPrice(),
                    decision.getMaxSpendUsdc(),
                    decision.getWinProbability(),
                    decision.getConfidence(),
                    decision.getEstimatedEdge(),
                    decision.getReason()
            );

            result = orderExecutor.execute(decision, context);
            log.info(
                    "AI Polymarket decision finished: decisionId={}, result={}, elapsedMs={}",
                    decisionId,
                    result,
                    System.currentTimeMillis() - startedAtMillis
            );
            return true;
        } catch (Exception e) {
            error = e.getMessage();
            log.error(
                    "AI Polymarket decision failed: decisionId={}, elapsedMs={}, error={}",
                    decisionId,
                    System.currentTimeMillis() - startedAtMillis,
                    e.getMessage(),
                    e
            );
            return false;
        } finally {
            persistAuditRecord(new PolymarketDecisionAuditRecord()
                    .setDecisionId(decisionId)
                    .setStartedAt(startedAt)
                    .setCompletedAt(Instant.now())
                    .setExecutionEnabled(properties.getExecution().isEnabled())
                    .setContext(context)
                    .setPrompt(prompt)
                    .setRawAiResponse(rawAiResponse)
                    .setAiDecision(decision)
                    .setOrderResult(result)
                    .setError(error));
            decisionLock.unlock();
        }
    }

    private void persistAuditRecord(PolymarketDecisionAuditRecord auditRecord) {
        try {
            auditSink.save(auditRecord);
        } catch (Exception e) {
            log.warn("Persist Polymarket AI decision audit record failed: {}", e.getMessage(), e);
        }
    }

    private Long persistAuditStart(Instant startedAt) {
        try {
            return auditSink.start(new PolymarketDecisionAuditRecord()
                    .setStartedAt(startedAt)
                    .setExecutionEnabled(properties.getExecution().isEnabled()));
        } catch (Exception e) {
            log.warn("Start Polymarket AI decision audit record failed: {}", e.getMessage(), e);
            return null;
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
                    .setSource("POLYMARKET")
                    .setPhase(phase)
                    .setRelatedId(decisionId == null ? null : decisionId.toString())
                    .setPromptText(prompt)
                    .setRawResponse(rawAiResponse)
                    .setErrorMessage(errorMessage)
                    .setFallbackAction(fallbackAction));
        } catch (Exception e) {
            log.warn("Persist Polymarket AI response parse error failed: {}", e.getMessage(), e);
        }
    }

    private static boolean isInvalidAiDecision(AiPolymarketDecision decision) {
        return decision != null
                && decision.getAction() != null
                && "HOLD".equals(decision.getAction().name())
                && decision.getReason() != null
                && decision.getReason().startsWith("Invalid Polymarket AI decision:");
    }
}
