package com.trade.polymarket.market;

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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolymarketMarketContextCollectorTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        );
        api.putOrderBook(
                "token-tight-no",
                orderBook("0.50", "100", "0.52", "100")
        );
        PolymarketMarketContextCollector collector = new PolymarketMarketContextCollector(api, properties);

        PolymarketDecisionContext context = collector.collect();

        assertEquals(1, context.getMarkets().size());
        assertEquals(1, context.getMarkets().getFirst().getOutcomes().size());
        assertEquals("No", context.getMarkets().getFirst().getOutcomes().getFirst().getOutcome());
    }

    private static GammaMarket restrictedTradableMarket() {
        return tradableMarket("market-1", "restricted-but-tradable", "token-yes", "token-no")
                .setRestricted(true);
    }

    private static GammaMarket tradableMarket(String id, String slug, String yesTokenId, String noTokenId) {
        return new GammaMarket()
                .setId(id)
                .setConditionId("condition-" + id)
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
                .setBids(List.of(new PolymarketOrderBookLevel().setPrice(bidPrice).setSize(bidSize)))
                .setAsks(List.of(new PolymarketOrderBookLevel().setPrice(askPrice).setSize(askSize)))
                .setMinOrderSize("5")
                .setTickSize("0.001")
                .setLastTradePrice(askPrice);
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
            return new PolymarketOrderBook()
                    .setBids(List.of(new PolymarketOrderBookLevel().setPrice("0.50").setSize("100")))
                    .setAsks(List.of(new PolymarketOrderBookLevel().setPrice("0.52").setSize("100")))
                    .setMinOrderSize("5")
                    .setTickSize("0.001")
                    .setLastTradePrice("0.51");
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
                            .setConditionId("condition-" + slug)
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
