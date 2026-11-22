package com.trade.polymarket.domain.rule;

import com.trade.polymarket.domain.model.*;
import com.trade.common.support.TradingMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Final trading eligibility, spend caps, precision, and order intent construction. */
public final class PolymarketOrderPolicy {
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final int MARKET_BUY_SIZE_SCALE = 4;
    public record Limits(BigDecimal minLimitPrice, BigDecimal maxLimitPrice,
                         BigDecimal minWinConfidenceScore, BigDecimal minExpectedEdge,
                         boolean requireAcceptingOrders, MarketEligibilityPolicy marketEligibilityPolicy,
                         BigDecimal maxOrderUsdc, BigDecimal minOrderSize, String orderType) {}
    public record Preparation(PolymarketOrderRequest request, String skipReason) {}
    private final Limits properties;
    public PolymarketOrderPolicy(Limits properties) { this.properties = properties; }
    public Preparation prepare(AiPolymarketDecision decision, PolymarketDecisionContext context, Instant now) {
        if (decision.getAction() == PolymarketAction.HOLD) { return new Preparation(null, "HOLD"); }
        var selection = context.findUniqueToken(decision.getTokenId()).orElse(null);
        if (selection == null) {
            return new Preparation(null, "tokenId is missing or ambiguous in collected Polymarket context");
        }
        var market = selection.market();
        var outcome = selection.outcome();
        String error = validateDecision(decision, market, outcome, now);
        if (error != null) { return new Preparation(null, error); }
        BigDecimal price = decision.getLimitPrice();
        // Clamp spend before calculating shares so both dry-run and live orders
        // use the exact risk-capped value.
        BigDecimal spendUsdc = marketBuySpend(decision.getMaxSpendUsdc());
        if (spendUsdc.signum() == 0) {
            return new Preparation(null, "Spend after applying maxOrderUsdc is below 0.01 USDC");
        }
        BigDecimal size = sharesForSpend(spendUsdc, price);
        BigDecimal minOrderSize = minOrderSize(market, outcome);
        if (size.compareTo(minOrderSize) < 0) {
            return new Preparation(null, "Calculated share size " + size + " is below minOrderSize " + minOrderSize);
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
                .setOrderType(properties.orderType())
                .setTickSize(firstText(outcome.getTickSize(), market.getOrderPriceMinTickSize()))
                .setNegRisk(outcome.getNegRisk() == null ? market.getNegRisk() : outcome.getNegRisk());

        return new Preparation(request, null);
    }

    private String validateDecision(AiPolymarketDecision decision, PolymarketMarketSnapshot market,
                                    PolymarketOutcomeSnapshot outcome, Instant now) {
        // These checks duplicate the prompt's hard gates. The prompt guides the
        // model, while this method is the final application-side guard.
        if (decision.getLimitPrice().compareTo(properties.minLimitPrice()) < 0
                || decision.getLimitPrice().compareTo(properties.maxLimitPrice()) > 0) {
            return "limitPrice outside configured range";
        }
        if (winConfidenceScore(decision).compareTo(properties.minWinConfidenceScore()) < 0) {
            return "winProbability * confidence below configured minimum";
        }
        if (decision.getEstimatedEdge().compareTo(properties.minExpectedEdge()) < 0) {
            return "estimatedEdge below configured minimum";
        }
        if (decision.getWinProbability().compareTo(decision.getLimitPrice().add(properties.minExpectedEdge())) < 0) {
            return "winProbability minus limitPrice below configured minimum";
        }
        if (outcome.getOutcome() == null || !outcome.getOutcome().strip().equalsIgnoreCase(decision.getOutcome())) {
            return "selected outcome does not match tokenId";
        }
        if (conflictingIdentifier(decision.getMarketId(), market.getId())
                || conflictingIdentifier(decision.getMarketSlug(), market.getSlug())) {
            return "selected market does not match tokenId";
        }
        String statusSkipReason = PolymarketMarketFilters.marketStatusSkipReason(properties.requireAcceptingOrders(),
                market.getActive(), market.getClosed(), market.getArchived(), market.getAcceptingOrders(), market.getEnableOrderBook());
        if (statusSkipReason != null) {
            return statusSkipReason;
        }
        String turnoverSkipReason = PolymarketMarketFilters.marketTurnoverSkipReason(
                properties.marketEligibilityPolicy(),
                market.getEndDate(),
                market.getVolume24hr(),
                market.getLiquidity(),
                now
        );
        if (turnoverSkipReason != null) {
            return turnoverSkipReason;
        }
        boolean immediate = "FAK".equalsIgnoreCase(properties.orderType()) || "FOK".equalsIgnoreCase(properties.orderType());
        return PolymarketMarketFilters.outcomeLiquiditySkipReason(properties.marketEligibilityPolicy(), outcome,
                immediate ? decision.getLimitPrice() : null);
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
        return properties.minOrderSize();
    }

    private static BigDecimal sharesForSpend(BigDecimal spendUsdc, BigDecimal price) {
        if (spendUsdc == null || price == null || price.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return spendUsdc.divide(price, MARKET_BUY_SIZE_SCALE, RoundingMode.DOWN).stripTrailingZeros();
    }

    private BigDecimal marketBuySpend(BigDecimal requestedSpend) {
        if (requestedSpend == null || properties.maxOrderUsdc() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal allowed = requestedSpend.min(properties.maxOrderUsdc());
        if (allowed.compareTo(ONE_CENT) < 0) {
            return BigDecimal.ZERO;
        }
        return allowed.setScale(ONE_CENT.scale(), RoundingMode.DOWN).stripTrailingZeros();
    }

    private static boolean conflictingIdentifier(String selected, String observed) {
        return selected != null && !selected.isBlank() && observed != null && !observed.isBlank()
                && !selected.equals(observed);
    }

    public static BigDecimal winConfidenceScore(AiPolymarketDecision decision) {
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
