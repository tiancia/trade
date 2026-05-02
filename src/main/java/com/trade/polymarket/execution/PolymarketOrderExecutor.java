package com.trade.polymarket.execution;

import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketAction;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketMarketSnapshot;
import com.trade.polymarket.model.PolymarketOrderRequest;
import com.trade.polymarket.model.PolymarketOrderResult;
import com.trade.polymarket.model.PolymarketOutcomeSnapshot;
import com.trade.trading.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
        if (decision.getAction() == PolymarketAction.HOLD) {
            log.info("Polymarket AI decision HOLD, no order placed. reason={}", decision.getReason());
            return PolymarketOrderResult.skipped("HOLD");
        }

        String validationError = validateDecision(decision, context);
        if (validationError != null) {
            log.info("Polymarket AI decision skipped: {}", validationError);
            return PolymarketOrderResult.skipped(validationError);
        }

        PolymarketOutcomeSnapshot outcome = context.findOutcomeByTokenId(decision.getTokenId()).orElseThrow();
        PolymarketMarketSnapshot market = context.findMarketByTokenId(decision.getTokenId()).orElseThrow();
        BigDecimal price = decision.getLimitPrice();
        BigDecimal spendUsdc = TradingMath.clamp(decision.getMaxSpendUsdc(), properties.getMaxOrderUsdc());
        BigDecimal size = sharesForSpend(spendUsdc, price);
        BigDecimal minOrderSize = minOrderSize(market, outcome);
        if (size.compareTo(minOrderSize) < 0) {
            return PolymarketOrderResult.skipped("Calculated share size " + size + " is below minOrderSize " + minOrderSize);
        }

        PolymarketOrderRequest request = new PolymarketOrderRequest()
                .setMarketSlug(market.getSlug())
                .setQuestion(market.getQuestion())
                .setOutcome(outcome.getOutcome())
                .setTokenId(outcome.getTokenId())
                .setPrice(price)
                .setSpendUsdc(spendUsdc)
                .setSize(size)
                .setOrderType(properties.getExecution().getOrderType())
                .setTickSize(firstText(outcome.getTickSize(), market.getOrderPriceMinTickSize()))
                .setNegRisk(outcome.getNegRisk() == null ? market.getNegRisk() : outcome.getNegRisk());

        if (!properties.getExecution().isEnabled()) {
            log.info("Polymarket execution dry-run: {}", request);
            return PolymarketOrderResult.dryRun(request.toString());
        }

        geoblockService.assertAllowed();
        String response = orderRunner.placeLimitBuy(request);
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
        if (decision.getLimitPrice().compareTo(properties.getMinLimitPrice()) < 0
                || decision.getLimitPrice().compareTo(properties.getMaxLimitPrice()) > 0) {
            return "limitPrice outside configured range";
        }
        if (decision.getConfidence().compareTo(properties.getMinConfidence()) < 0) {
            return "confidence below configured minimum";
        }
        if (decision.getEstimatedEdge().compareTo(properties.getMinExpectedEdge()) < 0) {
            return "estimatedEdge below configured minimum";
        }
        if (context.findOutcomeByTokenId(decision.getTokenId()).isEmpty()) {
            return "tokenId is not present in collected Polymarket context";
        }
        PolymarketMarketSnapshot market = context.findMarketByTokenId(decision.getTokenId()).orElse(null);
        if (market == null) {
            return "market is not present in collected Polymarket context";
        }
        if (Boolean.TRUE.equals(market.getClosed())
                || Boolean.TRUE.equals(market.getArchived())
                || Boolean.TRUE.equals(market.getRestricted())) {
            return "market is closed, archived, or restricted";
        }
        if (properties.isRequireAcceptingOrders()
                && (Boolean.FALSE.equals(market.getAcceptingOrders())
                || Boolean.FALSE.equals(market.getEnableOrderBook()))) {
            return "market is not accepting orders";
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
        return spendUsdc.divide(price, 6, RoundingMode.DOWN).stripTrailingZeros();
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
