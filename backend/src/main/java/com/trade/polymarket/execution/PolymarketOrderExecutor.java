package com.trade.polymarket.execution;

import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketAction;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketMarketSnapshot;
import com.trade.polymarket.model.PolymarketOrderRequest;
import com.trade.polymarket.model.PolymarketOrderResult;
import com.trade.polymarket.model.PolymarketOutcomeSnapshot;
import com.trade.polymarket.support.PolymarketMarketFilters;
import com.trade.trading.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Validates AI-selected Polymarket BUY decisions and turns them into order
 * runner payloads. Execution can run in dry-run mode for audit-only cycles.
 */
@Component
public class PolymarketOrderExecutor {
    private static final Logger log = LoggerFactory.getLogger(PolymarketOrderExecutor.class);
    private static final int MARKET_BUY_SPEND_SCALE = 2;
    private static final int MARKET_BUY_SIZE_SCALE = 4;

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
                winConfidenceScore(decision),
                decision.getEstimatedEdge(),
                properties.getExecution().isEnabled()
        );
        if (decision.getAction() == PolymarketAction.HOLD) {
            log.info("Polymarket AI decision HOLD, no order placed. reason={}", decision.getReason());
            return PolymarketOrderResult.skipped("HOLD");
        }

        String validationError = validateDecision(decision, context);
        if (validationError != null) {
            log.info(
                    "Polymarket AI decision skipped by validation: reason={}, minWinConfidenceScore={}, actualWinConfidenceScore={}, minExpectedEdge={}, priceRange=[{},{}]",
                    validationError,
                    properties.getMinWinConfidenceScore(),
                    winConfidenceScore(decision),
                    properties.getMinExpectedEdge(),
                    properties.getMinLimitPrice(),
                    properties.getMaxLimitPrice()
            );
            return PolymarketOrderResult.skipped(validationError);
        }

        PolymarketOutcomeSnapshot outcome = context.findOutcomeByTokenId(decision.getTokenId()).orElseThrow();
        PolymarketMarketSnapshot market = context.findMarketByTokenId(decision.getTokenId()).orElseThrow();
        BigDecimal price = decision.getLimitPrice();
        // Clamp spend before calculating shares so both dry-run and live orders
        // use the exact risk-capped value.
        BigDecimal spendUsdc = marketBuySpend(TradingMath.clamp(decision.getMaxSpendUsdc(), properties.getMaxOrderUsdc()));
        BigDecimal size = sharesForSpend(spendUsdc, price);
        BigDecimal minOrderSize = minOrderSize(market, outcome);
        if (size.compareTo(minOrderSize) < 0) {
            log.info(
                    "Polymarket AI decision skipped by order size: calculatedSize={}, minOrderSize={}, cappedSpendUsdc={}, price={}",
                    size,
                    minOrderSize,
                    spendUsdc,
                    price
            );
            return PolymarketOrderResult.skipped("Calculated share size " + size + " is below minOrderSize " + minOrderSize);
        }

        PolymarketOrderRequest request = new PolymarketOrderRequest()
                .setMarketSlug(market.getSlug())
                .setQuestion(market.getQuestion())
                .setOutcome(outcome.getOutcome())
                .setTokenId(outcome.getTokenId())
                .setSide("BUY")
                .setPrice(price)
                .setSpendUsdc(spendUsdc)
                .setSize(size)
                .setOrderType(properties.getExecution().getOrderType())
                .setTickSize(firstText(outcome.getTickSize(), market.getOrderPriceMinTickSize()))
                .setNegRisk(outcome.getNegRisk() == null ? market.getNegRisk() : outcome.getNegRisk());

        log.info(
                "Prepared Polymarket order request: marketSlug={}, outcome={}, tokenId={}, side={}, price={}, requestedSpendUsdc={}, cappedSpendUsdc={}, size={}, minOrderSize={}, orderType={}, tickSize={}, negRisk={}",
                request.getMarketSlug(),
                request.getOutcome(),
                request.getTokenId(),
                request.getSide(),
                request.getPrice(),
                decision.getMaxSpendUsdc(),
                request.getSpendUsdc(),
                request.getSize(),
                minOrderSize,
                request.getOrderType(),
                request.getTickSize(),
                request.getNegRisk()
        );

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

    private String validateDecision(AiPolymarketDecision decision, PolymarketDecisionContext context) {
        // These checks duplicate the prompt's hard gates. The prompt guides the
        // model, while this method is the final application-side guard.
        if (decision.getLimitPrice().compareTo(properties.getMinLimitPrice()) < 0
                || decision.getLimitPrice().compareTo(properties.getMaxLimitPrice()) > 0) {
            return "limitPrice outside configured range";
        }
        if (winConfidenceScore(decision).compareTo(properties.getMinWinConfidenceScore()) < 0) {
            return "winProbability * confidence below configured minimum";
        }
        if (decision.getEstimatedEdge().compareTo(properties.getMinExpectedEdge()) < 0) {
            return "estimatedEdge below configured minimum";
        }
        PolymarketOutcomeSnapshot outcome = context.findOutcomeByTokenId(decision.getTokenId()).orElse(null);
        if (outcome == null) {
            return "tokenId is not present in collected Polymarket context";
        }
        PolymarketMarketSnapshot market = context.findMarketByTokenId(decision.getTokenId()).orElse(null);
        if (market == null) {
            return "market is not present in collected Polymarket context";
        }
        if (Boolean.TRUE.equals(market.getClosed())
                || Boolean.TRUE.equals(market.getArchived())) {
            return "market is closed or archived";
        }
        if (properties.isRequireAcceptingOrders()
                && (Boolean.FALSE.equals(market.getAcceptingOrders())
                || Boolean.FALSE.equals(market.getEnableOrderBook()))) {
            return "market is not accepting orders";
        }
        String turnoverSkipReason = PolymarketMarketFilters.marketTurnoverSkipReason(
                properties,
                market.getEndDate(),
                market.getVolume24hr(),
                market.getLiquidity(),
                Instant.now()
        );
        if (turnoverSkipReason != null) {
            return turnoverSkipReason;
        }
        String outcomeSkipReason = PolymarketMarketFilters.outcomeLiquiditySkipReason(properties, outcome);
        if (outcomeSkipReason != null) {
            return outcomeSkipReason;
        }
        return null;
    }

    private BigDecimal minOrderSize(PolymarketMarketSnapshot market, PolymarketOutcomeSnapshot outcome) {
        BigDecimal outcomeMin = TradingMath.decimal(outcome.getMinOrderSize());
        if (outcomeMin.signum() > 0) {
            return outcomeMin;
        }
        BigDecimal marketMin = TradingMath.decimal(market.getOrderMinSize());
        if (marketMin.signum() > 0) {
            return marketMin;
        }
        return properties.getMinOrderSize();
    }

    private static BigDecimal sharesForSpend(BigDecimal spendUsdc, BigDecimal price) {
        if (spendUsdc == null || price == null || price.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return spendUsdc.divide(price, MARKET_BUY_SIZE_SCALE, RoundingMode.DOWN).stripTrailingZeros();
    }

    private static BigDecimal marketBuySpend(BigDecimal spendUsdc) {
        if (spendUsdc == null || spendUsdc.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return spendUsdc.setScale(MARKET_BUY_SPEND_SCALE, RoundingMode.DOWN).stripTrailingZeros();
    }

    private static BigDecimal winConfidenceScore(AiPolymarketDecision decision) {
        if (decision == null || decision.getWinProbability() == null || decision.getConfidence() == null) {
            return BigDecimal.ZERO;
        }
        return decision.getWinProbability().multiply(decision.getConfidence()).stripTrailingZeros();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
