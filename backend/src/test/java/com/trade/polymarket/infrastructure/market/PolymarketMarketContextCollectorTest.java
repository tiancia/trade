package com.trade.polymarket.infrastructure.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketApi;
import com.trade.client.polymarket.dto.GammaMarket;
import com.trade.client.polymarket.dto.PolymarketLastTradePrice;
import com.trade.client.polymarket.dto.PolymarketOrderBook;
import com.trade.client.polymarket.dto.PolymarketOrderBookLevel;
import com.trade.client.polymarket.dto.PolymarketSamplingMarket;
import com.trade.client.polymarket.dto.PolymarketSamplingMarketsPage;
import com.trade.client.polymarket.dto.PolymarketSamplingToken;
import com.trade.polymarket.domain.model.PolymarketDecisionContext;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymarketMarketContextCollectorTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BOOK_MARKET = "condition-test";

    @Test
    void alignsGammaOutcomesTokensAndPricesByTheirOriginalIndex() {
        var market = tradableMarket("m", "sample", "token-yes", "token-no");
        var collector = new PolymarketMarketContextCollector(new FakePolymarketApi(List.of(market)),
                new AiPolymarketProperties());
        market.setClobTokenIds(OBJECT_MAPPER.getNodeFactory().textNode("[null,\"token-no\"]"));
        var outcomes = collector.collect().getMarkets().getFirst().getOutcomes();
        assertEquals(1, outcomes.size());
        assertEquals("No", outcomes.getFirst().getOutcome());
        assertEquals("token-no", outcomes.getFirst().getTokenId());

        market.setClobTokenIds(OBJECT_MAPPER.valueToTree(List.of("token-yes", "token-no")))
                .setOutcomes(OBJECT_MAPPER.valueToTree(List.of(" ", "No")))
                .setOutcomePrices(OBJECT_MAPPER.createArrayNode().addNull().add("0.49"));
        outcomes = collector.collect().getMarkets().getFirst().getOutcomes();
        assertEquals(1, outcomes.size());
        assertEquals("token-no", outcomes.getFirst().getTokenId());
        assertEquals(new BigDecimal("0.49"), outcomes.getFirst().getGammaPrice());

        market.setOutcomes(OBJECT_MAPPER.valueToTree(List.of("Yes", "No")));
        outcomes = collector.collect().getMarkets().getFirst().getOutcomes();
        assertEquals(BigDecimal.ZERO, outcomes.getFirst().getGammaPrice());
        assertEquals(new BigDecimal("0.49"), outcomes.getLast().getGammaPrice());

        market.setOutcomePrices(OBJECT_MAPPER.valueToTree(List.of("0.49")));
        assertTrue(collector.collect().getMarkets().getFirst().getOutcomes().stream()
                .allMatch(outcome -> outcome.getGammaPrice().signum() == 0));
        market.setClobTokenIds(OBJECT_MAPPER.valueToTree(List.of("token-no")));
        assertTrue(collector.collect().getMarkets().isEmpty());
        market.setClobTokenIds(OBJECT_MAPPER.valueToTree(List.of("token-no", "token-no")));
        assertTrue(collector.collect().getMarkets().isEmpty());
    }

    @Test
    void onlyCollectsGammaMarketsMatchingEveryConfiguredMarketFilter() {
        var properties = new AiPolymarketProperties();
        properties.setMarketIds(List.of("allowed-id"));
        properties.setMarketSlugs(List.of("allowed-slug"));
        var api = new FakePolymarketApi(List.of(
                tradableMarket("other-id", "allowed-slug", "a", "b"),
                tradableMarket("allowed-id", "other-slug", "c", "d"),
                tradableMarket("allowed-id", "allowed-slug", "e", "f")));
        var markets = new PolymarketMarketContextCollector(api, properties).collect().getMarkets();
        assertEquals(1, markets.size());
        assertEquals("e", markets.getFirst().getOutcomes().getFirst().getTokenId());
    }

    @Test
    void rejectsBooksWithMissingOrConflictingIdentitiesEvenWithLiquidityFiltersOff() {
        var api = new OrderBookAwarePolymarketApi(List.of(restrictedTradableMarket()));
        var collector = new PolymarketMarketContextCollector(api, new AiPolymarketProperties());
        for (var book : List.of(
                orderBook("0.50", "10", "0.52", "10").setAssetId("token-no"),
                orderBook("0.50", "10", "0.52", "10").setAssetId(null),
                orderBook("0.50", "10", "0.52", "10").setAssetId("token-yes").setMarket("other-condition"),
                orderBook("0.50", "10", "0.52", "10").setAssetId("token-yes").setMarket(null))) {
            api.putOrderBook("token-yes", book);
            var outcomes = collector.collect().getMarkets().getFirst().getOutcomes();
            assertEquals(1, outcomes.size());
            assertEquals("token-no", outcomes.getFirst().getTokenId());
        }
    }

    @Test
    void checksExecutableBestPricesAndDepthIndependentlyOfResponseOrder() {
        var properties = new AiPolymarketProperties();
        properties.setClobTokenIds(List.of("token-yes"));
        properties.setOrderBookDepth(1);
        properties.setMaxOutcomeSpread(new BigDecimal("0.06"));
        var api = new OrderBookAwarePolymarketApi(List.of(restrictedTradableMarket()));
        var book = orderBook("0.20", "500", "0.95", "500").setAssetId("token-yes")
                .setBids(List.of(level("0.51", "0"), level("0.20", "500"), level("0.49", "10")))
                .setAsks(List.of(level("0.95", "500"), level("0.51", "0"), level("0.52", "5")));
        api.putOrderBook("token-yes", book);
        var collector = new PolymarketMarketContextCollector(api, properties);
        var outcome = collector.collect().getMarkets().getFirst().getOutcomes().getFirst();
        assertEquals(new BigDecimal("0.49"), outcome.getBestBid());
        assertEquals(new BigDecimal("0.52"), outcome.getBestAsk());
        assertEquals("0.49", outcome.getTopBids().getFirst().price());
        assertEquals("0.52", outcome.getTopAsks().getFirst().price());
        assertEquals(0, new BigDecimal("2.60").compareTo(outcome.getTopAskLiquidityUsdc()));

        properties.setMinOutcomeAskLiquidityUsdc(new BigDecimal("3"));
        assertTrue(collector.collect().getMarkets().isEmpty());
        properties.setMinOutcomeAskLiquidityUsdc(BigDecimal.ZERO);
        properties.setOrderBookDepth(0);
        outcome = collector.collect().getMarkets().getFirst().getOutcomes().getFirst();
        assertTrue(outcome.getTopAsks().isEmpty());
        assertEquals(new BigDecimal("0.52"), outcome.getBestAsk());
    }

    @Test
    void collectsRestrictedMarketsWhenTheyAcceptOrdersAndHaveOrderBooks() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setMarketLimit(5);

        PolymarketMarketContextCollector collector = new PolymarketMarketContextCollector(
                new FakePolymarketApi(List.of(restrictedTradableMarket())),
                properties
        );

        PolymarketDecisionContext context = collector.collect();

        assertEquals(1, context.getMarkets().size());
        assertEquals("restricted-but-tradable", context.getMarkets().getFirst().getSlug());
        assertEquals(2, context.getMarkets().getFirst().getOutcomes().size());
    }

    @Test
    void samplingAndGammaBothRequireConfirmedMarketStatus() {
        var properties = new AiPolymarketProperties();
        properties.setMarketDiscoverySource(AiPolymarketProperties.MarketDiscoverySource.CLOB_SAMPLING);
        var api = new FakePolymarketApi(List.of(restrictedTradableMarket().setAcceptingOrders(null))) {
            @Override
            public PolymarketSamplingMarketsPage listSamplingMarkets(Map<String, ?> queryParams) {
                var page = SamplingAwarePolymarketApi.samplingPage("sample", "sample-yes", "sample-no", null);
                page.getData().getFirst().setEnableOrderBook(null);
                return page;
            }
        };

        assertTrue(new PolymarketMarketContextCollector(api, properties).collect().getMarkets().isEmpty());
        assertEquals(1, api.queries().size());
    }

    @Test
    void configuredTokenCannotReviveARejectedMarket() {
        var properties = new AiPolymarketProperties();
        properties.setClobTokenIds(List.of("token-yes"));
        var closedMarket = restrictedTradableMarket().setClosed(true);
        var collector = new PolymarketMarketContextCollector(new FakePolymarketApi(List.of(closedMarket)), properties);

        assertTrue(collector.collect().getMarkets().isEmpty());
        properties.setRequireAcceptingOrders(false);
        assertTrue(collector.collect().getMarkets().isEmpty());
    }

    @Test
    void tokenOnlyFallbackPreservesUnknownStatusAndHonorsRequiredMetadata() {
        var properties = new AiPolymarketProperties();
        properties.setClobTokenIds(List.of("token-yes"));
        var collector = new PolymarketMarketContextCollector(new FakePolymarketApi(List.of()), properties);
        assertTrue(collector.collect().getMarkets().isEmpty());

        properties.setRequireAcceptingOrders(false);
        var fallback = collector.collect().getMarkets().getFirst();
        assertNull(fallback.getAcceptingOrders());
        assertNull(fallback.getEnableOrderBook());
        assertEquals("token-yes", fallback.getOutcomes().getFirst().getTokenId());

        properties.setRequireMarketEndDate(true);
        assertTrue(collector.collect().getMarkets().isEmpty());
        properties.setRequireMarketEndDate(false);
        properties.setMarketSlugs(List.of("specified-market"));
        assertTrue(collector.collect().getMarkets().isEmpty());
    }

    @Test
    void lifetimeVolumeCannotStandInForMissingDailyVolume() {
        var properties = new AiPolymarketProperties();
        var market = restrictedTradableMarket().setVolume24hr(null).setVolume("5000").setVolumeNum("5000");
        var collector = new PolymarketMarketContextCollector(new FakePolymarketApi(List.of(market)), properties);
        assertNull(collector.collect().getMarkets().getFirst().getVolume24hr());

        properties.setMinMarketVolume24hr(new BigDecimal("100"));
        assertTrue(collector.collect().getMarkets().isEmpty());
        market.setVolume24hr("100");
        assertEquals("100", collector.collect().getMarkets().getFirst().getVolume24hr());
    }

    @Test
    void rotatesDefaultMarketDiscoveryOffsetAcrossWindow() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setMarketLimit(2);
        properties.setMarketDiscoveryWindow(6);
        OffsetAwarePolymarketApi api = new OffsetAwarePolymarketApi();
        PolymarketMarketContextCollector collector = new PolymarketMarketContextCollector(api, properties);

        PolymarketDecisionContext first = collector.collect();
        PolymarketDecisionContext second = collector.collect();
        PolymarketDecisionContext third = collector.collect();
        PolymarketDecisionContext fourth = collector.collect();

        assertEquals(List.of(0, 2, 4, 0), api.queries().stream()
                .map(query -> query.get("offset"))
                .toList());
        assertEquals("offset-0-market", first.getMarkets().getFirst().getSlug());
        assertEquals("offset-2-market", second.getMarkets().getFirst().getSlug());
        assertEquals("offset-4-market", third.getMarkets().getFirst().getSlug());
        assertEquals("offset-0-market", fourth.getMarkets().getFirst().getSlug());
        Map<?, ?> marketSelection = (Map<?, ?>) second.getAiParameters().get("marketSelection");
        assertEquals(2, marketSelection.get("marketDiscoveryOffset"));
        assertEquals(6, marketSelection.get("marketDiscoveryWindow"));
    }

    @Test
    void collectsClobSamplingMarketsAndAdvancesCursor() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setMarketDiscoverySource(AiPolymarketProperties.MarketDiscoverySource.CLOB_SAMPLING);
        properties.setMarketLimit(1);
        SamplingAwarePolymarketApi api = new SamplingAwarePolymarketApi();
        PolymarketMarketContextCollector collector = new PolymarketMarketContextCollector(api, properties);

        PolymarketDecisionContext first = collector.collect();
        PolymarketDecisionContext second = collector.collect();

        assertEquals("sampling-one", first.getMarkets().getFirst().getSlug());
        assertEquals("sampling-two", second.getMarkets().getFirst().getSlug());
        assertEquals(List.of("null", "cursor-1"), api.samplingQueries().stream()
                .map(query -> String.valueOf(query.get("next_cursor")))
                .toList());
        Map<?, ?> marketSelection = (Map<?, ?>) second.getAiParameters().get("marketSelection");
        assertEquals("CLOB_SAMPLING", marketSelection.get("marketDiscoverySource"));
        assertEquals("cursor-1", marketSelection.get("samplingMarketsCursor"));
        assertEquals("cursor-2", marketSelection.get("samplingMarketsNextCursor"));
    }

    @Test
    void filtersMarketsOutsideConfiguredShortTermResolutionWindow() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setMarketLimit(5);
        properties.setRequireMarketEndDate(true);
        properties.setMinTimeToResolutionMinutes(15);
        properties.setMaxTimeToResolutionHours(72);
        PolymarketMarketContextCollector collector = new PolymarketMarketContextCollector(
                new FakePolymarketApi(List.of(
                        tradableMarket("market-1", "next-week-market", "token-long-yes", "token-long-no")
                                .setEndDate(Instant.now().plus(Duration.ofDays(7)).toString()),
                        tradableMarket("market-2", "next-hour-market", "token-soon-yes", "token-soon-no")
                                .setEndDate(Instant.now().plus(Duration.ofHours(2)).toString())
                )),
                properties
        );

        PolymarketDecisionContext context = collector.collect();

        assertEquals(1, context.getMarkets().size());
        assertEquals("next-hour-market", context.getMarkets().getFirst().getSlug());
    }

    @Test
    void filtersOutcomesWithWideSpreadOrInsufficientAskLiquidity() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setMarketLimit(1);
        properties.setMaxOutcomeSpread(new BigDecimal("0.03"));
        properties.setMinOutcomeAskLiquidityUsdc(new BigDecimal("20"));
        OrderBookAwarePolymarketApi api = new OrderBookAwarePolymarketApi(List.of(
                tradableMarket("market-1", "tight-liquid-market", "token-wide-yes", "token-tight-no")
        ));
        api.putOrderBook(
                "token-wide-yes",
                orderBook("0.40", "100", "0.48", "100")
                        .setAssetId("token-wide-yes")
        );
        api.putOrderBook(
                "token-tight-no",
                orderBook("0.50", "100", "0.52", "100")
                        .setAssetId("token-tight-no")
        );
        PolymarketMarketContextCollector collector = new PolymarketMarketContextCollector(api, properties);

        PolymarketDecisionContext context = collector.collect();

        assertEquals(1, context.getMarkets().size());
        assertEquals(1, context.getMarkets().getFirst().getOutcomes().size());
        assertEquals("No", context.getMarkets().getFirst().getOutcomes().getFirst().getOutcome());
    }

    @Test
    void copiesDepthWithoutChangingJsonPrecisionOrLiquidity() {
        var properties = new AiPolymarketProperties();
        properties.setOrderBookDepth(1);
        var book = orderBook("0.5000", "100.00", "0.5200", "100.00");
        var original = book.getBids().getFirst();
        var originalJson = OBJECT_MAPPER.valueToTree(original);
        book.setBids(List.of(original, new PolymarketOrderBookLevel().setPrice("0.49").setSize("500")));
        var api = new FakePolymarketApi(List.of(restrictedTradableMarket())) {
            @Override public PolymarketOrderBook getOrderBook(String tokenId) { return book.setAssetId(tokenId); }
        };
        var outcome = new PolymarketMarketContextCollector(api, properties).collect()
                .getMarkets().getFirst().getOutcomes().getFirst();
        original.setPrice("0.10").setSize("1");
        assertEquals(1, outcome.getTopBids().size());
        assertEquals(originalJson, OBJECT_MAPPER.valueToTree(outcome.getTopBids().getFirst()));
        assertEquals(0, new BigDecimal("50").compareTo(outcome.getTopBidLiquidityUsdc()));
        assertEquals(0, new BigDecimal("0.50").compareTo(outcome.getBestBid()));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> outcome.getTopBids().clear());
    }

    private static GammaMarket restrictedTradableMarket() {
        return tradableMarket("market-1", "restricted-but-tradable", "token-yes", "token-no")
                .setRestricted(true);
    }

    private static GammaMarket tradableMarket(String id, String slug, String yesTokenId, String noTokenId) {
        return new GammaMarket()
                .setId(id)
                .setConditionId(BOOK_MARKET)
                .setSlug(slug)
                .setQuestion("Will it happen?")
                .setClosed(false)
                .setArchived(false)
                .setRestricted(false)
                .setAcceptingOrders(true)
                .setEnableOrderBook(true)
                .setOutcomes(OBJECT_MAPPER.valueToTree(List.of("Yes", "No")))
                .setClobTokenIds(OBJECT_MAPPER.valueToTree(List.of(yesTokenId, noTokenId)))
                .setOutcomePrices(OBJECT_MAPPER.valueToTree(List.of("0.51", "0.49")))
                .setVolume24hr("1000")
                .setLiquidityNum("500")
                .setOrderMinSize("5")
                .setOrderPriceMinTickSize("0.001");
    }

    private static PolymarketOrderBook orderBook(
            String bidPrice,
            String bidSize,
            String askPrice,
            String askSize
    ) {
        return new PolymarketOrderBook()
                .setMarket(BOOK_MARKET)
                .setBids(List.of(level(bidPrice, bidSize)))
                .setAsks(List.of(level(askPrice, askSize)))
                .setMinOrderSize("5")
                .setTickSize("0.001")
                .setLastTradePrice(askPrice);
    }

    private static PolymarketOrderBookLevel level(String price, String size) {
        return new PolymarketOrderBookLevel().setPrice(price).setSize(size);
    }

    private static class FakePolymarketApi extends PolymarketApi {
        private final List<GammaMarket> markets;
        private final List<Map<String, ?>> queries = new ArrayList<>();

        FakePolymarketApi(List<GammaMarket> markets) {
            super(null);
            this.markets = markets;
        }

        @Override
        public List<GammaMarket> listMarkets(Map<String, ?> queryParams) {
            queries.add(new LinkedHashMap<>(queryParams));
            return markets;
        }

        List<Map<String, ?>> queries() {
            return queries;
        }

        @Override
        public PolymarketOrderBook getOrderBook(String tokenId) {
            return orderBook("0.50", "100", "0.52", "100").setAssetId(tokenId).setLastTradePrice("0.51");
        }

        @Override
        public PolymarketLastTradePrice getLastTradePrice(String tokenId) {
            return new PolymarketLastTradePrice().setPrice("0.51");
        }
    }

    private static class OffsetAwarePolymarketApi extends FakePolymarketApi {
        OffsetAwarePolymarketApi() {
            super(List.of());
        }

        @Override
        public List<GammaMarket> listMarkets(Map<String, ?> queryParams) {
            super.listMarkets(queryParams);
            int offset = ((Number) queryParams.get("offset")).intValue();
            return List.of(tradableMarket(
                    "market-" + offset,
                    "offset-" + offset + "-market",
                    "token-" + offset + "-yes",
                    "token-" + offset + "-no"
            ));
        }
    }

    private static class OrderBookAwarePolymarketApi extends FakePolymarketApi {
        private final Map<String, PolymarketOrderBook> orderBooks = new LinkedHashMap<>();

        OrderBookAwarePolymarketApi(List<GammaMarket> markets) {
            super(markets);
        }

        void putOrderBook(String tokenId, PolymarketOrderBook orderBook) {
            orderBooks.put(tokenId, orderBook);
        }

        @Override
        public PolymarketOrderBook getOrderBook(String tokenId) {
            return orderBooks.getOrDefault(tokenId, super.getOrderBook(tokenId));
        }
    }

    private static class SamplingAwarePolymarketApi extends FakePolymarketApi {
        private final List<Map<String, ?>> samplingQueries = new ArrayList<>();

        SamplingAwarePolymarketApi() {
            super(List.of());
        }

        @Override
        public PolymarketSamplingMarketsPage listSamplingMarkets(Map<String, ?> queryParams) {
            samplingQueries.add(new LinkedHashMap<>(queryParams));
            if ("cursor-1".equals(queryParams.get("next_cursor"))) {
                return samplingPage("sampling-two", "sample-token-2-yes", "sample-token-2-no", "cursor-2");
            }
            return samplingPage("sampling-one", "sample-token-1-yes", "sample-token-1-no", "cursor-1");
        }

        List<Map<String, ?>> samplingQueries() {
            return samplingQueries;
        }

        private static PolymarketSamplingMarketsPage samplingPage(
                String slug,
                String yesTokenId,
                String noTokenId,
                String nextCursor
        ) {
            return new PolymarketSamplingMarketsPage()
                    .setLimit(1000)
                    .setCount(1000)
                    .setNextCursor(nextCursor)
                    .setData(List.of(new PolymarketSamplingMarket()
                            .setConditionId(BOOK_MARKET)
                            .setQuestionId("question-" + slug)
                            .setMarketSlug(slug)
                            .setQuestion("Will the sampled market happen?")
                            .setDescription("Sampled market")
                            .setActive(true)
                            .setClosed(false)
                            .setArchived(false)
                            .setAcceptingOrders(true)
                            .setEnableOrderBook(true)
                            .setMinimumOrderSize("5")
                            .setMinimumTickSize("0.001")
                            .setNegRisk(false)
                            .setTokens(List.of(
                                    new PolymarketSamplingToken()
                                            .setTokenId(yesTokenId)
                                            .setOutcome("Yes")
                                            .setPrice("0.48"),
                                    new PolymarketSamplingToken()
                                            .setTokenId(noTokenId)
                                            .setOutcome("No")
                                            .setPrice("0.52")
                            ))));
        }
    }
}
