package com.trade.polymarket.application;

import com.trade.client.ai.AiTextClient;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.decision.AiPolymarketDecisionParser;
import com.trade.polymarket.decision.AiPolymarketPromptBuilder;
import com.trade.polymarket.execution.PolymarketOrderExecutor;
import com.trade.polymarket.market.PolymarketMarketContextCollector;
import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketOrderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

@Component
public class AiPolymarketService {
    private static final Logger log = LoggerFactory.getLogger(AiPolymarketService.class);

    private final AiTextClient aiTextClient;
    private final PolymarketMarketContextCollector contextCollector;
    private final AiPolymarketPromptBuilder promptBuilder;
    private final AiPolymarketDecisionParser decisionParser;
    private final PolymarketOrderExecutor orderExecutor;
    private final AiPolymarketProperties properties;
    private final ReentrantLock decisionLock = new ReentrantLock();

    public AiPolymarketService(
            AiTextClient aiTextClient,
            PolymarketMarketContextCollector contextCollector,
            AiPolymarketPromptBuilder promptBuilder,
            AiPolymarketDecisionParser decisionParser,
            PolymarketOrderExecutor orderExecutor,
            AiPolymarketProperties properties
    ) {
        this.aiTextClient = aiTextClient;
        this.contextCollector = contextCollector;
        this.promptBuilder = promptBuilder;
        this.decisionParser = decisionParser;
        this.orderExecutor = orderExecutor;
        this.properties = properties;
    }

    public boolean runDecision() {
        if (!properties.isEnabled()) {
            log.info("AI Polymarket module is disabled");
            return false;
        }
        if (!decisionLock.tryLock()) {
            log.info("AI Polymarket decision is already running");
            return false;
        }

        try {
            PolymarketDecisionContext context = contextCollector.collect();
            if (context.getMarkets() == null || context.getMarkets().isEmpty()) {
                log.info("AI Polymarket decision skipped: no eligible markets collected");
                return false;
            }
            log.info("AI Polymarket parameters:\n {}", context.getAiParametersJson());

            String prompt = promptBuilder.buildPrompt(context.getAiParametersJson());
            String rawAiResponse = aiTextClient.generateJson(prompt);
            log.info("AI Polymarket raw decision response:\n {}", rawAiResponse);

            AiPolymarketDecision decision = decisionParser.parse(rawAiResponse);
            log.info(
                    "AI Polymarket parsed decision: action={}, marketSlug={}, outcome={}, tokenId={}, price={}, spendUsdc={}, reason={}",
                    decision.getAction(),
                    decision.getMarketSlug(),
                    decision.getOutcome(),
                    decision.getTokenId(),
                    decision.getLimitPrice(),
                    decision.getMaxSpendUsdc(),
                    decision.getReason()
            );

            PolymarketOrderResult result = orderExecutor.execute(decision, context);
            log.info("AI Polymarket execution result: {}", result);
            return true;
        } catch (Exception e) {
            log.error("AI Polymarket decision failed: {}", e.getMessage(), e);
            return false;
        } finally {
            decisionLock.unlock();
        }
    }
}
