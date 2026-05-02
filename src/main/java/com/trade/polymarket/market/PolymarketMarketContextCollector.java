package com.trade.polymarket.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketApi;
import com.trade.client.polymarket.dto.GammaMarket;
import com.trade.client.polymarket.dto.PolymarketLastTradePrice;
import com.trade.client.polymarket.dto.PolymarketOrderBook;
import com.trade.client.polymarket.dto.PolymarketOrderBookLevel;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketMarketSnapshot;
import com.trade.polymarket.model.PolymarketOutcomeSnapshot;
import com.trade.polymarket.support.PolymarketJsonLists;
import com.trade.trading.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PolymarketMarketContextCollector {
    private static final Logger log = LoggerFactory.getLogger(PolymarketMarketContextCollector.class);

    private final PolymarketApi polymarketApi;
    private final AiPolymarketProperties properties;
    private final ObjectMapper objectMapper;

    public PolymarketMarketContextCollector(PolymarketApi polymarketApi, AiPolymarketProperties properties) {
        this.polymarketApi = polymarketApi;
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public PolymarketDecisionContext collect() {
        List<PolymarketMarketSnapshot> markets = collectMarketSnapshots();
        Map<String, Object> parameters = buildParameters(markets);
        return new PolymarketDecisionContext()
                .setMarkets(markets)
                .setAiParameters(parameters)
                .setAiParametersJson(toJson(parameters));
    }

    private List<PolymarketMarketSnapshot> collectMarketSnapshots() {
        List<GammaMarket> gammaMarkets = listConfiguredMarkets();
        List<PolymarketMarketSnapshot> snapshots = new ArrayList<>();
        for (GammaMarket market : gammaMarkets) {
            if (!isTradableMarket(market)) {
                continue;
            }
            snapshots.add(toSnapshot(market));
            if (snapshots.size() >= properties.getMarketLimit()) {
                break;
            }
        }

        if (snapshots.isEmpty() && !properties.getClobTokenIds().isEmpty()) {
            snapshots.add(configuredTokenSnapshot(properties.getClobTokenIds()));
        }
        return snapshots;
    }

    private List<GammaMarket> listConfiguredMarkets() {
        if (!properties.getMarketSlugs().isEmpty()
                || !properties.getMarketIds().isEmpty()
                || !properties.getClobTokenIds().isEmpty()) {
            Map<String, Object> query = new LinkedHashMap<>();
            if (!properties.getMarketSlugs().isEmpty()) {
                query.put("slug", properties.getMarketSlugs());
            }
            if (!properties.getMarketIds().isEmpty()) {
                query.put("id", properties.getMarketIds());
            }
            if (!properties.getClobTokenIds().isEmpty()) {
                query.put("clob_token_ids", properties.getClobTokenIds());
            }
            query.put("active", true);
            query.put("closed", false);
            query.put("limit", Math.max(properties.getMarketLimit(), 1));
            return polymarketApi.listMarkets(query);
        }

        return polymarketApi.listMarkets(Map.of(
                "active", true,
                "closed", false,
                "order", "volume_24hr",
                "ascending", false,
                "limit", Math.max(properties.getMarketLimit(), 1)
        ));
    }

    private boolean isTradableMarket(GammaMarket market) {
        if (market == null) {
            return false;
        }
        if (Boolean.TRUE.equals(market.getClosed())
                || Boolean.TRUE.equals(market.getArchived())
                || Boolean.TRUE.equals(market.getRestricted())) {
            return false;
        }
        if (properties.isRequireAcceptingOrders()) {
            if (Boolean.FALSE.equals(market.getAcceptingOrders())) {
                return false;
            }
            return !Boolean.FALSE.equals(market.getEnableOrderBook());
        }
        return true;
    }

    private PolymarketMarketSnapshot toSnapshot(GammaMarket market) {
        List<String> outcomes = PolymarketJsonLists.stringList(market.getOutcomes());
        List<String> tokenIds = PolymarketJsonLists.stringList(market.getClobTokenIds());
        List<String> outcomePrices = PolymarketJsonLists.stringList(market.getOutcomePrices());
        List<PolymarketOutcomeSnapshot> outcomeSnapshots = new ArrayList<>();

        int outcomeCount = Math.min(outcomes.size(), tokenIds.size());
        for (int i = 0; i < outcomeCount; i++) {
            String tokenId = tokenIds.get(i);
            if (isFilteredOutToken(tokenId)) {
                continue;
            }
            BigDecimal gammaPrice = i < outcomePrices.size()
                    ? TradingMath.decimal(outcomePrices.get(i))
                    : BigDecimal.ZERO;
            outcomeSnapshots.add(outcomeSnapshot(
                    outcomes.get(i),
                    tokenId,
                    gammaPrice,
                    market.getOrderMinSize(),
                    market.getOrderPriceMinTickSize(),
                    market.getNegRisk()
            ));
        }

        return new PolymarketMarketSnapshot()
                .setId(market.getId())
                .setConditionId(market.getConditionId())
                .setSlug(market.getSlug())
                .setQuestion(market.getQuestion())
                .setDescription(market.getDescription())
                .setCategory(market.getCategory())
                .setEndDate(market.getEndDate())
                .setActive(market.getActive())
                .setClosed(market.getClosed())
                .setArchived(market.getArchived())
                .setRestricted(market.getRestricted())
                .setEnableOrderBook(market.getEnableOrderBook())
                .setAcceptingOrders(market.getAcceptingOrders())
                .setVolume24hr(firstText(market.getVolume24hr(), market.getVolumeNum(), market.getVolume()))
                .setLiquidity(firstText(market.getLiquidityNum(), market.getLiquidity()))
                .setOrderMinSize(market.getOrderMinSize())
                .setOrderPriceMinTickSize(market.getOrderPriceMinTickSize())
                .setNegRisk(market.getNegRisk())
                .setOutcomes(outcomeSnapshots);
    }

    private PolymarketMarketSnapshot configuredTokenSnapshot(List<String> tokenIds) {
        List<PolymarketOutcomeSnapshot> outcomes = tokenIds.stream()
                .filter(tokenId -> tokenId != null && !tokenId.isBlank())
                .map(tokenId -> outcomeSnapshot(tokenId, tokenId, BigDecimal.ZERO, null, null, null))
                .toList();
        return new PolymarketMarketSnapshot()
                .setQuestion("Configured Polymarket CLOB tokens")
                .setAcceptingOrders(true)
                .setEnableOrderBook(true)
                .setOutcomes(outcomes);
    }

    private boolean isFilteredOutToken(String tokenId) {
        return !properties.getClobTokenIds().isEmpty() && !properties.getClobTokenIds().contains(tokenId);
    }

    private PolymarketOutcomeSnapshot outcomeSnapshot(
            String outcome,
            String tokenId,
            BigDecimal gammaPrice,
            String marketMinOrderSize,
            String marketTickSize,
            Boolean marketNegRisk
    ) {
        PolymarketOutcomeSnapshot snapshot = new PolymarketOutcomeSnapshot()
                .setOutcome(outcome)
                .setTokenId(tokenId)
                .setGammaPrice(gammaPrice)
                .setMinOrderSize(marketMinOrderSize)
                .setTickSize(marketTickSize)
                .setNegRisk(marketNegRisk);

        try {
            PolymarketOrderBook orderBook = polymarketApi.getOrderBook(tokenId);
            List<PolymarketOrderBookLevel> bids = nullToEmpty(orderBook.getBids());
            List<PolymarketOrderBookLevel> asks = nullToEmpty(orderBook.getAsks());
            BigDecimal bestBid = bestBid(bids);
            BigDecimal bestAsk = bestAsk(asks);
            snapshot.setBestBid(bestBid)
                    .setBestAsk(bestAsk)
                    .setMidPrice(midPrice(bestBid, bestAsk))
                    .setSpread(bestAsk.signum() > 0 && bestBid.signum() > 0 ? bestAsk.subtract(bestBid) : BigDecimal.ZERO)
                    .setTopBids(trimLevels(bids))
                    .setTopAsks(trimLevels(asks))
                    .setMinOrderSize(firstText(orderBook.getMinOrderSize(), marketMinOrderSize))
                    .setTickSize(firstText(orderBook.getTickSize(), marketTickSize))
                    .setNegRisk(orderBook.getNegRisk() == null ? marketNegRisk : orderBook.getNegRisk())
                    .setLastTradePrice(TradingMath.decimal(orderBook.getLastTradePrice()));
        } catch (Exception e) {
            log.warn("Collect Polymarket order book failed, tokenId={}, error={}", tokenId, e.getMessage());
            snapshot.setOrderBookError(e.getMessage());
        }

        if (snapshot.getLastTradePrice() == null || snapshot.getLastTradePrice().signum() <= 0) {
            snapshot.setLastTradePrice(lastTradePrice(tokenId).orElse(BigDecimal.ZERO));
        }
        return snapshot;
    }

    private Optional<BigDecimal> lastTradePrice(String tokenId) {
        try {
            PolymarketLastTradePrice price = polymarketApi.getLastTradePrice(tokenId);
            BigDecimal value = TradingMath.decimal(price.getPrice());
            return value.signum() > 0 ? Optional.of(value) : Optional.empty();
        } catch (Exception e) {
            log.debug("Collect Polymarket last trade price failed, tokenId={}, error={}", tokenId, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> buildParameters(List<PolymarketMarketSnapshot> markets) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("timestamp", Instant.now().toString());
        parameters.put("exchange", "Polymarket");
        parameters.put("allowedActions", List.of("BUY", "HOLD"));
        parameters.put("executionMode", properties.getExecution().isEnabled() ? "live_order_enabled" : "dry_run_only");
        parameters.put("riskLimits", Map.of(
                "maxOrderUsdc", properties.getMaxOrderUsdc(),
                "minConfidence", properties.getMinConfidence(),
                "minExpectedEdge", properties.getMinExpectedEdge(),
                "minLimitPrice", properties.getMinLimitPrice(),
                "maxLimitPrice", properties.getMaxLimitPrice(),
                "minOrderSize", properties.getMinOrderSize()
        ));
        parameters.put("marketSelection", Map.of(
                "marketSlugs", properties.getMarketSlugs(),
                "marketIds", properties.getMarketIds(),
                "clobTokenIds", properties.getClobTokenIds(),
                "marketLimit", properties.getMarketLimit()
        ));
        parameters.put("markets", markets);
        return parameters;
    }

    private String toJson(Map<String, Object> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (Exception e) {
            throw new IllegalStateException("Serialize Polymarket AI parameters failed", e);
        }
    }

    private List<PolymarketOrderBookLevel> trimLevels(List<PolymarketOrderBookLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.stream()
                .limit(Math.max(properties.getOrderBookDepth(), 0))
                .toList();
    }

    private static BigDecimal bestBid(List<PolymarketOrderBookLevel> bids) {
        return bids.stream()
                .map(level -> TradingMath.decimal(level.getPrice()))
                .filter(value -> value.signum() > 0)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    private static BigDecimal bestAsk(List<PolymarketOrderBookLevel> asks) {
        return asks.stream()
                .map(level -> TradingMath.decimal(level.getPrice()))
                .filter(value -> value.signum() > 0)
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    private static BigDecimal midPrice(BigDecimal bid, BigDecimal ask) {
        if (bid == null || ask == null || bid.signum() <= 0 || ask.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return bid.add(ask).divide(new BigDecimal("2"));
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
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
