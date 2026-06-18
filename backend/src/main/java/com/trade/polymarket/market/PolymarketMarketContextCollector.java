package com.trade.polymarket.market;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketApi;
import com.trade.client.polymarket.dto.GammaMarket;
import com.trade.client.polymarket.dto.PolymarketLastTradePrice;
import com.trade.client.polymarket.dto.PolymarketOrderBook;
import com.trade.client.polymarket.dto.PolymarketOrderBookLevel;
import com.trade.client.polymarket.dto.PolymarketSamplingMarket;
import com.trade.client.polymarket.dto.PolymarketSamplingMarketsPage;
import com.trade.client.polymarket.dto.PolymarketSamplingToken;
import com.trade.polymarket.config.AiPolymarketProperties;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketMarketSnapshot;
import com.trade.polymarket.model.PolymarketOutcomeSnapshot;
import com.trade.polymarket.support.PolymarketJsonLists;
import com.trade.polymarket.support.PolymarketMarketFilters;
import com.trade.common.support.TradingMath;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovers tradable Polymarket markets and enriches each outcome with CLOB
 * order-book data for AI decision making.
 */
@Component
public class PolymarketMarketContextCollector {
    private static final Logger log = LoggerFactory.getLogger(PolymarketMarketContextCollector.class);

    private final PolymarketApi polymarketApi;
    private final AiPolymarketProperties properties;
    private final ObjectMapper objectMapper;
    // Discovery cursors rotate through market pages across runs so the AI does
    // not repeatedly inspect only the same top-volume markets.
    private final AtomicInteger marketDiscoveryOffset = new AtomicInteger();
    private final AtomicReference<String> samplingMarketsCursor = new AtomicReference<>();

    public PolymarketMarketContextCollector(PolymarketApi polymarketApi, AiPolymarketProperties properties) {
        this.polymarketApi = polymarketApi;
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public PolymarketDecisionContext collect() {
        log.info(
                "Collect Polymarket context started: marketLimit={}, discoverySource={}, requireAcceptingOrders={}, maxTimeToResolutionHours={}, maxOutcomeSpread={}, slugs={}, ids={}, clobTokenIds={}",
                properties.getMarketLimit(),
                properties.getMarketDiscoverySource(),
                properties.isRequireAcceptingOrders(),
                properties.getMaxTimeToResolutionHours(),
                properties.getMaxOutcomeSpread(),
                properties.getMarketSlugs(),
                properties.getMarketIds(),
                properties.getClobTokenIds()
        );
        MarketSnapshotBatch batch = collectMarketSnapshots();
        List<PolymarketMarketSnapshot> markets = batch.markets();
        Map<String, Object> parameters = buildParameters(batch);
        log.info(
                "Collect Polymarket context finished: marketCount={}, outcomeCount={}, executionMode={}",
                markets.size(),
                outcomeCount(markets),
                properties.getExecution().isEnabled() ? "live_order_enabled" : "dry_run_only"
        );
        return new PolymarketDecisionContext()
                .setMarkets(markets)
                .setAiParameters(parameters)
                .setAiParametersJson(toJson(parameters));
    }

    private MarketSnapshotBatch collectMarketSnapshots() {
        // Prefer CLOB sampling when no explicit market filters are configured,
        // then fall back to Gamma volume discovery if sampling yields nothing.
        if (!hasConfiguredMarketFilters()
                && properties.getMarketDiscoverySource() == AiPolymarketProperties.MarketDiscoverySource.CLOB_SAMPLING) {
            try {
                MarketSnapshotBatch samplingBatch = collectSamplingMarketSnapshots();
                if (!samplingBatch.markets().isEmpty()) {
                    return samplingBatch;
                }
                log.info("No eligible CLOB sampling markets collected; fallback to Gamma volume discovery");
            } catch (Exception e) {
                log.warn("Collect CLOB sampling markets failed; fallback to Gamma volume discovery. error={}", e.getMessage());
            }
        }

        MarketCandidateBatch candidateBatch = listGammaMarkets();
        List<GammaMarket> gammaMarkets = candidateBatch.markets();
        log.info("Polymarket Gamma market candidates fetched: count={}", gammaMarkets.size());
        List<PolymarketMarketSnapshot> snapshots = new ArrayList<>();
        for (GammaMarket market : gammaMarkets) {
            String skipReason = tradabilitySkipReason(market);
            if (skipReason != null) {
                log.info(
                        "Skip Polymarket market candidate: id={}, slug={}, reason={}",
                        market == null ? null : market.getId(),
                        market == null ? null : market.getSlug(),
                        skipReason
                );
                continue;
            }
            PolymarketMarketSnapshot snapshot = toSnapshot(market);
            if (snapshot.getOutcomes() == null || snapshot.getOutcomes().isEmpty()) {
                log.info(
                        "Skip Polymarket market candidate with no usable outcomes: id={}, slug={}",
                        market.getId(),
                        market.getSlug()
                );
                continue;
            }
            snapshots.add(snapshot);
            log.info(
                    "Collected Polymarket market snapshot: id={}, slug={}, question={}, outcomeCount={}",
                    snapshot.getId(),
                    snapshot.getSlug(),
                    snapshot.getQuestion(),
                    snapshot.getOutcomes() == null ? 0 : snapshot.getOutcomes().size()
            );
            if (snapshots.size() >= properties.getMarketLimit()) {
                break;
            }
        }

        if (snapshots.isEmpty() && !properties.getClobTokenIds().isEmpty()) {
            log.info(
                    "No Gamma market matched configured token ids; building token-only Polymarket context: tokenCount={}",
                    properties.getClobTokenIds().size()
            );
            PolymarketMarketSnapshot snapshot = configuredTokenSnapshot(properties.getClobTokenIds());
            if (snapshot.getOutcomes() != null && !snapshot.getOutcomes().isEmpty()) {
                snapshots.add(snapshot);
            }
        }
        return new MarketSnapshotBatch(
                snapshots,
                candidateBatch.discoveryOffset(),
                candidateBatch.discoveryWindow(),
                candidateBatch.discoverySource(),
                null,
                null
        );
    }

    private MarketSnapshotBatch collectSamplingMarketSnapshots() {
        String requestCursor = samplingCursorForRequest();
        Map<String, Object> query = new LinkedHashMap<>();
        if (requestCursor != null) {
            query.put("next_cursor", requestCursor);
        }
        log.info("List Polymarket CLOB sampling markets: cursor={}, query={}", requestCursor, query);
        PolymarketSamplingMarketsPage page = polymarketApi.listSamplingMarkets(query);
        updateSamplingCursor(requestCursor, page == null ? null : page.getNextCursor());

        List<PolymarketSamplingMarket> candidates = nullToEmpty(page == null ? null : page.getData());
        log.info(
                "Polymarket CLOB sampling market candidates fetched: count={}, pageCount={}, pageLimit={}, cursor={}, nextCursor={}",
                candidates.size(),
                page == null ? null : page.getCount(),
                page == null ? null : page.getLimit(),
                requestCursor,
                page == null ? null : page.getNextCursor()
        );
        List<PolymarketMarketSnapshot> snapshots = new ArrayList<>();
        for (PolymarketSamplingMarket market : candidates) {
            String skipReason = tradabilitySkipReason(market);
            if (skipReason != null) {
                log.info(
                        "Skip Polymarket sampling market candidate: conditionId={}, slug={}, reason={}",
                        market == null ? null : market.getConditionId(),
                        market == null ? null : market.getMarketSlug(),
                        skipReason
                );
                continue;
            }
            PolymarketMarketSnapshot snapshot = toSnapshot(market);
            if (snapshot.getOutcomes() == null || snapshot.getOutcomes().isEmpty()) {
                log.info(
                        "Skip Polymarket sampling market candidate with no usable outcomes: conditionId={}, slug={}",
                        market.getConditionId(),
                        market.getMarketSlug()
                );
                continue;
            }
            snapshots.add(snapshot);
            log.info(
                    "Collected Polymarket sampling market snapshot: id={}, slug={}, question={}, outcomeCount={}",
                    snapshot.getId(),
                    snapshot.getSlug(),
                    snapshot.getQuestion(),
                    snapshot.getOutcomes().size()
            );
            if (snapshots.size() >= properties.getMarketLimit()) {
                break;
            }
        }
        return new MarketSnapshotBatch(
                snapshots,
                null,
                null,
                properties.getMarketDiscoverySource().name(),
                requestCursor,
                page == null ? null : page.getNextCursor()
        );
    }

    private MarketCandidateBatch listGammaMarkets() {
        int marketLimit = effectiveMarketLimit();
        if (hasConfiguredMarketFilters()) {
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
            query.put("limit", marketLimit);
            log.info("List configured Polymarket markets: query={}", query);
            return new MarketCandidateBatch(polymarketApi.listMarkets(query), null, null, "GAMMA_CONFIGURED");
        }

        int discoveryWindow = effectiveMarketDiscoveryWindow(marketLimit);
        int discoveryOffset = nextDiscoveryOffset(marketLimit, discoveryWindow);
        int queryLimit = turnoverFilterEnabled() ? discoveryWindow : marketLimit;
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("active", true);
        query.put("closed", false);
        query.put("order", "volume_24hr");
        query.put("ascending", false);
        query.put("limit", queryLimit);
        query.put("offset", discoveryOffset);
        log.info(
                "List top Polymarket markets by 24h volume: offset={}, window={}, queryLimit={}, query={}",
                discoveryOffset,
                discoveryWindow,
                queryLimit,
                query
        );
        return new MarketCandidateBatch(
                polymarketApi.listMarkets(query),
                discoveryOffset,
                discoveryWindow,
                AiPolymarketProperties.MarketDiscoverySource.GAMMA_VOLUME.name()
        );
    }

    private boolean hasConfiguredMarketFilters() {
        return !properties.getMarketSlugs().isEmpty()
                || !properties.getMarketIds().isEmpty()
                || !properties.getClobTokenIds().isEmpty();
    }

    private String samplingCursorForRequest() {
        String cursor = samplingMarketsCursor.get();
        if (hasText(cursor)) {
            return cursor.trim();
        }
        String initialCursor = properties.getSamplingMarketsInitialCursor();
        return hasText(initialCursor) ? initialCursor.trim() : null;
    }

    private void updateSamplingCursor(String requestCursor, String nextCursor) {
        if (hasText(nextCursor) && !nextCursor.trim().equals(requestCursor)) {
            samplingMarketsCursor.set(nextCursor.trim());
            return;
        }
        samplingMarketsCursor.set(null);
    }

    private String tradabilitySkipReason(GammaMarket market) {
        if (market == null) {
            return "market is null";
        }
        if (Boolean.TRUE.equals(market.getClosed())
                || Boolean.TRUE.equals(market.getArchived())) {
            return "market is closed or archived";
        }
        if (properties.isRequireAcceptingOrders()) {
            if (Boolean.FALSE.equals(market.getAcceptingOrders())) {
                return "market is not accepting orders";
            }
            if (Boolean.FALSE.equals(market.getEnableOrderBook())) {
                return "market order book is disabled";
            }
        }
        return PolymarketMarketFilters.marketTurnoverSkipReason(
                properties,
                market.getEndDate(),
                firstText(market.getVolume24hr(), market.getVolumeNum(), market.getVolume()),
                firstText(market.getLiquidityNum(), market.getLiquidity()),
                Instant.now()
        );
    }

    private String tradabilitySkipReason(PolymarketSamplingMarket market) {
        if (market == null) {
            return "market is null";
        }
        if (Boolean.TRUE.equals(market.getClosed())
                || Boolean.TRUE.equals(market.getArchived())) {
            return "market is closed or archived";
        }
        if (properties.isRequireAcceptingOrders()) {
            if (Boolean.FALSE.equals(market.getAcceptingOrders())) {
                return "market is not accepting orders";
            }
            if (Boolean.FALSE.equals(market.getEnableOrderBook())) {
                return "market order book is disabled";
            }
        }
        return PolymarketMarketFilters.marketTurnoverSkipReason(
                properties,
                market.getEndDateIso(),
                null,
                null,
                Instant.now()
        );
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
            addOutcomeIfEligible(outcomeSnapshots, market.getSlug(), outcomeSnapshot(
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
                .setTimeToResolutionMinutes(PolymarketMarketFilters.timeToResolutionMinutes(market.getEndDate(), Instant.now()))
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

    private PolymarketMarketSnapshot toSnapshot(PolymarketSamplingMarket market) {
        List<PolymarketOutcomeSnapshot> outcomeSnapshots = new ArrayList<>();
        for (PolymarketSamplingToken token : nullToEmpty(market.getTokens())) {
            if (token == null || !hasText(token.getTokenId()) || isFilteredOutToken(token.getTokenId())) {
                continue;
            }
            addOutcomeIfEligible(outcomeSnapshots, market.getMarketSlug(), outcomeSnapshot(
                    firstText(token.getOutcome(), token.getTokenId()),
                    token.getTokenId(),
                    TradingMath.decimal(token.getPrice()),
                    market.getMinimumOrderSize(),
                    market.getMinimumTickSize(),
                    market.getNegRisk()
            ));
        }

        return new PolymarketMarketSnapshot()
                .setId(firstText(market.getQuestionId(), market.getConditionId()))
                .setConditionId(market.getConditionId())
                .setSlug(market.getMarketSlug())
                .setQuestion(market.getQuestion())
                .setDescription(market.getDescription())
                .setEndDate(market.getEndDateIso())
                .setTimeToResolutionMinutes(PolymarketMarketFilters.timeToResolutionMinutes(market.getEndDateIso(), Instant.now()))
                .setActive(market.getActive())
                .setClosed(market.getClosed())
                .setArchived(market.getArchived())
                .setEnableOrderBook(market.getEnableOrderBook())
                .setAcceptingOrders(market.getAcceptingOrders())
                .setOrderMinSize(market.getMinimumOrderSize())
                .setOrderPriceMinTickSize(market.getMinimumTickSize())
                .setNegRisk(market.getNegRisk())
                .setOutcomes(outcomeSnapshots);
    }

    private PolymarketMarketSnapshot configuredTokenSnapshot(List<String> tokenIds) {
        List<PolymarketOutcomeSnapshot> outcomes = new ArrayList<>();
        tokenIds.stream()
                .filter(tokenId -> tokenId != null && !tokenId.isBlank())
                .map(tokenId -> outcomeSnapshot(tokenId, tokenId, BigDecimal.ZERO, null, null, null))
                .forEach(outcome -> addOutcomeIfEligible(outcomes, "configured-token", outcome));
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
            // CLOB book data is the executable view; Gamma prices are kept only
            // as fallback/context when book or last-trade calls fail.
            PolymarketOrderBook orderBook = polymarketApi.getOrderBook(tokenId);
            List<PolymarketOrderBookLevel> bids = nullToEmpty(orderBook.getBids());
            List<PolymarketOrderBookLevel> asks = nullToEmpty(orderBook.getAsks());
            List<PolymarketOrderBookLevel> topBids = trimLevels(bids);
            List<PolymarketOrderBookLevel> topAsks = trimLevels(asks);
            BigDecimal bestBid = bestBid(bids);
            BigDecimal bestAsk = bestAsk(asks);
            snapshot.setBestBid(bestBid)
                    .setBestAsk(bestAsk)
                    .setMidPrice(midPrice(bestBid, bestAsk))
                    .setSpread(bestAsk.signum() > 0 && bestBid.signum() > 0 ? bestAsk.subtract(bestBid) : BigDecimal.ZERO)
                    .setTopBids(topBids)
                    .setTopAsks(topAsks)
                    .setTopBidLiquidityUsdc(liquidityUsdc(topBids))
                    .setTopAskLiquidityUsdc(liquidityUsdc(topAsks))
                    .setMinOrderSize(firstText(orderBook.getMinOrderSize(), marketMinOrderSize))
                    .setTickSize(firstText(orderBook.getTickSize(), marketTickSize))
                    .setNegRisk(orderBook.getNegRisk() == null ? marketNegRisk : orderBook.getNegRisk())
                    .setLastTradePrice(TradingMath.decimal(orderBook.getLastTradePrice()));
            log.info(
                    "Collected Polymarket order book: tokenId={}, outcome={}, bidLevels={}, askLevels={}, bestBid={}, bestAsk={}, spread={}, lastTradePrice={}",
                    tokenId,
                    outcome,
                    bids.size(),
                    asks.size(),
                    snapshot.getBestBid(),
                    snapshot.getBestAsk(),
                    snapshot.getSpread(),
                    snapshot.getLastTradePrice()
            );
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

    private Map<String, Object> buildParameters(MarketSnapshotBatch batch) {
        List<PolymarketMarketSnapshot> markets = batch.markets();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("timestamp", Instant.now().toString());
        parameters.put("exchange", "Polymarket");
        parameters.put("allowedActions", List.of("BUY", "HOLD"));
        parameters.put("executionMode", properties.getExecution().isEnabled() ? "live_order_enabled" : "dry_run_only");
        parameters.put("riskLimits", Map.of(
                "maxOrderUsdc", properties.getMaxOrderUsdc(),
                "minWinConfidenceScore", properties.getMinWinConfidenceScore(),
                "minExpectedEdge", properties.getMinExpectedEdge(),
                "minLimitPrice", properties.getMinLimitPrice(),
                "maxLimitPrice", properties.getMaxLimitPrice(),
                "minOrderSize", properties.getMinOrderSize()
        ));
        parameters.put("turnoverFilters", Map.of(
                "requireMarketEndDate", properties.isRequireMarketEndDate(),
                "minTimeToResolutionMinutes", properties.getMinTimeToResolutionMinutes(),
                "maxTimeToResolutionHours", properties.getMaxTimeToResolutionHours(),
                "minMarketVolume24hr", properties.getMinMarketVolume24hr(),
                "minMarketLiquidity", properties.getMinMarketLiquidity(),
                "maxOutcomeSpread", properties.getMaxOutcomeSpread(),
                "minOutcomeAskLiquidityUsdc", properties.getMinOutcomeAskLiquidityUsdc()
        ));
        Map<String, Object> marketSelection = new LinkedHashMap<>();
        marketSelection.put("marketSlugs", properties.getMarketSlugs());
        marketSelection.put("marketIds", properties.getMarketIds());
        marketSelection.put("clobTokenIds", properties.getClobTokenIds());
        marketSelection.put("marketLimit", properties.getMarketLimit());
        marketSelection.put("marketDiscoverySource", batch.discoverySource());
        if (batch.discoveryOffset() != null) {
            marketSelection.put("marketDiscoveryOffset", batch.discoveryOffset());
            marketSelection.put("marketDiscoveryWindow", batch.discoveryWindow());
        }
        if (batch.samplingMarketsCursor() != null) {
            marketSelection.put("samplingMarketsCursor", batch.samplingMarketsCursor());
        }
        if (batch.samplingMarketsNextCursor() != null) {
            marketSelection.put("samplingMarketsNextCursor", batch.samplingMarketsNextCursor());
        }
        parameters.put("marketSelection", marketSelection);
        parameters.put("markets", markets);
        return parameters;
    }

    private int effectiveMarketLimit() {
        return Math.max(properties.getMarketLimit(), 1);
    }

    private int effectiveMarketDiscoveryWindow(int marketLimit) {
        return Math.max(properties.getMarketDiscoveryWindow(), marketLimit);
    }

    private boolean turnoverFilterEnabled() {
        return properties.isRequireMarketEndDate()
                || properties.getMinTimeToResolutionMinutes() > 0
                || properties.getMaxTimeToResolutionHours() > 0
                || isPositive(properties.getMinMarketVolume24hr())
                || isPositive(properties.getMinMarketLiquidity())
                || isPositive(properties.getMaxOutcomeSpread())
                || isPositive(properties.getMinOutcomeAskLiquidityUsdc());
    }

    private int nextDiscoveryOffset(int marketLimit, int discoveryWindow) {
        int maxOffset = Math.max(discoveryWindow - marketLimit, 0);
        while (true) {
            // CAS keeps the rotating offset safe if multiple schedulers/tests
            // ever call collect concurrently.
            int current = marketDiscoveryOffset.get();
            int offset = current < 0 || current > maxOffset ? 0 : current;
            int nextOffset = offset >= maxOffset ? 0 : offset + marketLimit;
            if (marketDiscoveryOffset.compareAndSet(current, nextOffset)) {
                return offset;
            }
        }
    }

    private static int outcomeCount(List<PolymarketMarketSnapshot> markets) {
        return markets.stream()
                .mapToInt(market -> market.getOutcomes() == null ? 0 : market.getOutcomes().size())
                .sum();
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

    private void addOutcomeIfEligible(
            List<PolymarketOutcomeSnapshot> outcomes,
            String marketSlug,
            PolymarketOutcomeSnapshot outcome
    ) {
        String skipReason = PolymarketMarketFilters.outcomeLiquiditySkipReason(properties, outcome);
        if (skipReason != null) {
            log.info(
                    "Skip Polymarket outcome candidate: marketSlug={}, outcome={}, tokenId={}, reason={}",
                    marketSlug,
                    outcome == null ? null : outcome.getOutcome(),
                    outcome == null ? null : outcome.getTokenId(),
                    skipReason
            );
            return;
        }
        outcomes.add(outcome);
    }

    private static BigDecimal liquidityUsdc(List<PolymarketOrderBookLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return levels.stream()
                .map(level -> TradingMath.decimal(level.getPrice()).multiply(TradingMath.decimal(level.getSize())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .stripTrailingZeros();
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private record MarketCandidateBatch(
            List<GammaMarket> markets,
            Integer discoveryOffset,
            Integer discoveryWindow,
            String discoverySource
    ) {
    }

    private record MarketSnapshotBatch(
            List<PolymarketMarketSnapshot> markets,
            Integer discoveryOffset,
            Integer discoveryWindow,
            String discoverySource,
            String samplingMarketsCursor,
            String samplingMarketsNextCursor
    ) {
    }
}
