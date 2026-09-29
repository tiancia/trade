package com.trade.polymarket.application.execution;

import com.trade.polymarket.domain.rule.PolymarketOrderPolicy;
import com.trade.polymarket.application.port.PolymarketOrderRunner;
import com.trade.polymarket.domain.model.AiPolymarketDecision;
import com.trade.polymarket.domain.model.PolymarketDecisionContext;
import com.trade.polymarket.domain.model.PolymarketOrderRequest;
import com.trade.polymarket.domain.model.PolymarketOrderResult;
import com.trade.polymarket.infrastructure.broker.PolymarketGeoblockService;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Validates AI-selected Polymarket BUY decisions and turns them into order
 * runner payloads. Execution can run in dry-run mode for audit-only cycles.
 */
@Component
public class PolymarketOrderExecutor {
    private static final Logger log = LoggerFactory.getLogger(PolymarketOrderExecutor.class);

    private final AiPolymarketProperties properties;
    private final PolymarketOrderRunner orderRunner;
    private final PolymarketGeoblockService geoblockService;

    public PolymarketOrderExecutor(
            AiPolymarketProperties properties,
            PolymarketOrderRunner orderRunner,
            PolymarketGeoblockService geoblockService
    ) {
        this.properties = properties;
        this.orderRunner = orderRunner;
        this.geoblockService = geoblockService;
    }

    public PolymarketOrderResult execute(AiPolymarketDecision decision, PolymarketDecisionContext context) {
        log.info(
                "Evaluate Polymarket AI decision for execution: action={}, marketSlug={}, outcome={}, tokenId={}, price={}, spendUsdc={}, winProbability={}, confidence={}, winConfidenceScore={}, estimatedEdge={}, executionEnabled={}",
                decision.getAction(),
                decision.getMarketSlug(),
                decision.getOutcome(),
                decision.getTokenId(),
                decision.getLimitPrice(),
                decision.getMaxSpendUsdc(),
                decision.getWinProbability(),
                decision.getConfidence(),
                PolymarketOrderPolicy.winConfidenceScore(decision),
                decision.getEstimatedEdge(),
                properties.getExecution().isEnabled()
        );
        var policy = new PolymarketOrderPolicy(new PolymarketOrderPolicy.Limits(
                properties.getMinLimitPrice(), properties.getMaxLimitPrice(), properties.getMinWinConfidenceScore(),
                properties.getMinExpectedEdge(), properties.isRequireAcceptingOrders(), properties.marketEligibilityPolicy(),
                properties.getMaxOrderUsdc(), properties.getMinOrderSize(), properties.getExecution().getOrderType()));
        var prepared = policy.prepare(decision, context, Instant.now());
        if (prepared.skipReason() != null) {
            log.info("Polymarket decision skipped: {}", prepared.skipReason());
            return PolymarketOrderResult.skipped(prepared.skipReason());
        }
        PolymarketOrderRequest request = prepared.request();
        if (!properties.getExecution().isEnabled()) {
            log.info("Polymarket execution dry-run: {}", request);
            return PolymarketOrderResult.dryRun(request.toString());
        }

        log.info("Polymarket live execution enabled, running geoblock check before placing order");
        geoblockService.assertAllowed();
        log.info("Polymarket geoblock check passed, invoking order runner");
        String response = orderRunner.placeOrder(request);
        log.info("Polymarket order placed: marketSlug={}, outcome={}, tokenId={}, price={}, size={}, response={}",
                request.getMarketSlug(),
                request.getOutcome(),
                request.getTokenId(),
                request.getPrice(),
                request.getSize(),
                response);
        return PolymarketOrderResult.placed(response);
    }

}
