package com.trade.polymarket.application.execution;

import com.trade.client.polymarket.PolymarketApi;
import com.trade.polymarket.port.PolymarketOrderRunner;
import com.trade.polymarket.domain.model.AiPolymarketDecision;
import com.trade.polymarket.domain.model.MarketDepthLevel;
import com.trade.polymarket.domain.model.PolymarketAction;
import com.trade.polymarket.domain.model.PolymarketDecisionContext;
import com.trade.polymarket.domain.model.PolymarketMarketSnapshot;
import com.trade.polymarket.domain.model.PolymarketOrderRequest;
import com.trade.polymarket.domain.model.PolymarketOrderResult;
import com.trade.polymarket.domain.model.PolymarketOutcomeSnapshot;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import com.trade.polymarket.infrastructure.broker.PolymarketGeoblockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PolymarketOrderExecutorTest {
    @Test
    void disabledModuleNeverStartsLivePreflightOrSubmits() {
        var properties = liveProperties();
        properties.setEnabled(false);
        var runner = new RecordingRunner();
        var geoblock = mock(PolymarketGeoblockService.class);

        var result = new PolymarketOrderExecutor(properties, runner, geoblock).execute(buyDecision(), context());

        assertEquals("SKIPPED", result.getStatus());
        assertFalse(runner.called);
        verifyNoInteractions(geoblock);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void moduleDisabledDuringGeoblockCheckNeverSubmits(boolean restart) {
        var properties = liveProperties();
        var runner = new RecordingRunner();
        var geoblock = mock(PolymarketGeoblockService.class);
        doAnswer(invocation -> {
            properties.setEnabled(false);
            if (restart) properties.setEnabled(true);
            return null;
        }).when(geoblock).assertAllowed();

        var result = new PolymarketOrderExecutor(properties, runner, geoblock).execute(buyDecision(), context());

        assertEquals("SKIPPED", result.getStatus());
        assertFalse(runner.called);
        verify(geoblock).assertAllowed();
    }

    @Test
    void executionDisabledDuringGeoblockCheckNeverSubmits() {
        var properties = liveProperties();
        var runner = new RecordingRunner();
        var geoblock = mock(PolymarketGeoblockService.class);
        doAnswer(invocation -> { properties.getExecution().setEnabled(false); return null; }).when(geoblock).assertAllowed();

        var result = new PolymarketOrderExecutor(properties, runner, geoblock).execute(buyDecision(), context());

        assertEquals("SKIPPED", result.getStatus());
        assertFalse(runner.called);
    }

    @Test
    void enabledLiveExecutionStillSubmitsOnceAfterGeoblockCheck() {
        var properties = liveProperties();
        var runner = mock(PolymarketOrderRunner.class);
        var geoblock = mock(PolymarketGeoblockService.class);
        when(runner.placeOrder(any())).thenReturn("accepted");

        var result = new PolymarketOrderExecutor(properties, runner, geoblock).execute(buyDecision(), context());

        assertEquals("PLACED", result.getStatus());
        var calls = inOrder(geoblock, runner);
        calls.verify(geoblock).assertAllowed();
        calls.verify(runner).placeOrder(any());
        verifyNoMoreInteractions(runner, geoblock);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"blocked\":null}", "{\"blocked\":\"false\"}",
            "{\"blocked\":false,\"restricted\":true}", "{\"restricted\":0,\"blocked\":false}",
            "{\"blocked\":true,\"blocked\":false}", "{\"blocked\":false} {}"})
    void missingInvalidOrRestrictedRegionalDecisionStopsBeforeOrderSubmission(String response) {
        var properties = liveProperties();
        var runner = mock(PolymarketOrderRunner.class);
        var api = mock(PolymarketApi.class);
        when(api.getRawUrl(properties.getExecution().getGeoblockUrl())).thenReturn(response);
        var executor = new PolymarketOrderExecutor(properties, runner, new PolymarketGeoblockService(api, properties));

        assertThrows(IllegalStateException.class, () -> executor.execute(buyDecision(), context()));
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"blocked\":false,\"country\":\"XX\"}", "{\"geoblocked\":false}", "{\"restricted\":false}"})
    void explicitRegionalClearanceAllowsOrderSubmission(String response) {
        var properties = liveProperties();
        var runner = new RecordingRunner();
        var api = mock(PolymarketApi.class);
        when(api.getRawUrl(properties.getExecution().getGeoblockUrl())).thenReturn(response);
        var executor = new PolymarketOrderExecutor(properties, runner, new PolymarketGeoblockService(api, properties));

        assertEquals("PLACED", executor.execute(buyDecision(), context()).getStatus());
        assertTrue(runner.called);
    }

    private static AiPolymarketProperties liveProperties() {
        var properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.getExecution().setEnabled(true);
        properties.setMinWinConfidenceScore(new BigDecimal("0.45"));
        return properties;
    }

    @Test
    void dryRunBuildsCappedBuyOrderWithoutCallingRunner() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.setMaxOrderUsdc(new BigDecimal("5"));
        properties.setMinOrderSize(new BigDecimal("1"));
        properties.setMinWinConfidenceScore(new BigDecimal("0.45"));
        RecordingRunner runner = new RecordingRunner();
        PolymarketOrderExecutor executor = new PolymarketOrderExecutor(properties, runner, null);

        PolymarketOrderResult result = executor.execute(buyDecision(), context());

        assertTrue(result.isDryRun());
        assertFalse(runner.called);
        assertTrue(result.getResponseBody().contains("side=BUY"));
        assertTrue(result.getResponseBody().contains("spendUsdc=5"));
    }

    @Test
    void dryRunRoundsMarketBuyAmountsToPolymarketPrecision() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.setMaxOrderUsdc(new BigDecimal("5.129"));
        properties.setMinOrderSize(new BigDecimal("1"));
        properties.setMinWinConfidenceScore(new BigDecimal("0.45"));
        RecordingRunner runner = new RecordingRunner();
        PolymarketOrderExecutor executor = new PolymarketOrderExecutor(properties, runner, null);

        AiPolymarketDecision decision = buyDecision()
                .setLimitPrice(new BigDecimal("0.93"))
                .setWinProbability(new BigDecimal("0.98"))
                .setEstimatedProbability(new BigDecimal("0.98"))
                .setEstimatedEdge(new BigDecimal("0.05"))
                .setMaxSpendUsdc(new BigDecimal("8"));
        PolymarketOrderResult result = executor.execute(decision, context());

        assertTrue(result.isDryRun());
        assertTrue(result.getResponseBody().contains("spendUsdc=5.12"));
        assertTrue(result.getResponseBody().contains("size=5.5053"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "0.009"})
    void unusableSpendingCapStopsBeforeLiveSubmission(BigDecimal cap) {
        var properties = liveProperties();
        properties.setMaxOrderUsdc(cap);
        properties.setMinOrderSize(BigDecimal.ZERO);
        var context = context();
        context.getMarkets().getFirst().getOutcomes().getFirst().setMinOrderSize(null);
        var runner = mock(PolymarketOrderRunner.class);
        var geoblock = mock(PolymarketGeoblockService.class);

        var result = new PolymarketOrderExecutor(properties, runner, geoblock).execute(buyDecision(), context);

        assertEquals("SKIPPED", result.getStatus());
        verifyNoInteractions(geoblock, runner);
    }

    @Test
    void checksTheEdgeAtTheRequestedPriceEvenWhenTheModelReportsEnoughEdge() {
        var properties = liveProperties();
        var runner = mock(PolymarketOrderRunner.class);
        var geoblock = mock(PolymarketGeoblockService.class);
        var executor = new PolymarketOrderExecutor(properties, runner, geoblock);
        var decision = buyDecision().setLimitPrice(new BigDecimal("0.58"));

        var result = executor.execute(decision, context());

        assertEquals("SKIPPED", result.getStatus());
        assertEquals("winProbability minus limitPrice below configured minimum", result.getSkipReason());
        verifyNoInteractions(geoblock, runner);

        decision.setLimitPrice(new BigDecimal("0.57")).setEstimatedEdge(new BigDecimal("0.03"));
        assertEquals("PLACED", executor.execute(decision, context()).getStatus());
        verify(runner).placeOrder(any());
    }

    @Test
    void contradictorySelectionCannotSubmitAnotherOutcomesToken() {
        var runner = mock(PolymarketOrderRunner.class);
        var geoblock = mock(PolymarketGeoblockService.class);
        var executor = new PolymarketOrderExecutor(liveProperties(), runner, geoblock);
        var context = context();
        context.getMarkets().getFirst().setId("market-1");

        assertEquals("SKIPPED", executor.execute(buyDecision().setOutcome("No"), context).getStatus());
        assertEquals("SKIPPED", executor.execute(buyDecision().setMarketId("market-2"), context).getStatus());
        assertEquals("SKIPPED", executor.execute(buyDecision().setMarketSlug("other-market"), context).getStatus());
        verifyNoInteractions(geoblock, runner);

        var matching = buyDecision().setOutcome("YES").setMarketId("market-1").setMarketSlug("market");
        assertEquals("PLACED", executor.execute(matching, context).getStatus());
        verify(runner).placeOrder(any());
    }

    @Test
    void ambiguousTokenMappingNeverReachesTheRunner() {
        var runner = mock(PolymarketOrderRunner.class);
        var geoblock = mock(PolymarketGeoblockService.class);
        var executor = new PolymarketOrderExecutor(liveProperties(), runner, geoblock);
        var context = context();
        var market = context.getMarkets().getFirst();
        var otherOutcome = new PolymarketOutcomeSnapshot().setTokenId("token-1").setOutcome("No");
        var otherMarket = new PolymarketMarketSnapshot().setSlug("other").setOutcomes(List.of(otherOutcome));
        context.setMarkets(List.of(market, otherMarket));
        assertEquals("SKIPPED", executor.execute(buyDecision(), context).getStatus());

        context.setMarkets(List.of(market));
        market.setOutcomes(List.of(market.getOutcomes().getFirst(), otherOutcome));
        assertEquals("SKIPPED", executor.execute(buyDecision(), context).getStatus());
        verifyNoInteractions(geoblock, runner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAK", "FOK"})
    void onlyAsksAtOrBelowTheBuyLimitSatisfyTheLiquidityMinimum(String orderType) {
        var properties = liveProperties();
        properties.getExecution().setOrderType(orderType);
        properties.setMinOutcomeAskLiquidityUsdc(new BigDecimal("25"));
        var runner = mock(PolymarketOrderRunner.class);
        var geoblock = mock(PolymarketGeoblockService.class);
        var executor = new PolymarketOrderExecutor(properties, runner, geoblock);
        var context = context();
        var outcome = context.getMarkets().getFirst().getOutcomes().getFirst();
        outcome.setTopAskLiquidityUsdc(new BigDecimal("76"));
        assertEquals("SKIPPED", executor.execute(buyDecision(), context).getStatus());
        outcome.setTopAsks(List.of(new MarketDepthLevel("0.50", "2"), new MarketDepthLevel("0.75", "100")));
        assertEquals("SKIPPED", executor.execute(buyDecision(), context).getStatus());
        verifyNoInteractions(geoblock, runner);

        outcome.setTopAsks(List.of(new MarketDepthLevel("0.40", "50"), new MarketDepthLevel("0.50", "10"),
                new MarketDepthLevel("0.75", "68")));
        assertEquals("PLACED", executor.execute(buyDecision(), context).getStatus());
        verify(runner).placeOrder(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GTC", "GTD"})
    void restingBuyRetainsTheMarketDepthCheck(String orderType) {
        var properties = liveProperties();
        properties.getExecution().setOrderType(orderType);
        properties.setMinOutcomeAskLiquidityUsdc(new BigDecimal("25"));
        var runner = mock(PolymarketOrderRunner.class);
        var context = context();
        context.getMarkets().getFirst().getOutcomes().getFirst()
                .setTopAsks(List.of(new MarketDepthLevel("0.75", "40")))
                .setTopAskLiquidityUsdc(new BigDecimal("30"));
        var executor = new PolymarketOrderExecutor(properties, runner, mock(PolymarketGeoblockService.class));
        assertEquals("PLACED", executor.execute(buyDecision(), context).getStatus());
        verify(runner).placeOrder(any());
    }

    @Test
    void skipsWhenEdgeIsTooSmall() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.setMinExpectedEdge(new BigDecimal("0.10"));
        properties.setMinWinConfidenceScore(new BigDecimal("0.45"));
        PolymarketOrderExecutor executor = new PolymarketOrderExecutor(properties, new RecordingRunner(), null);

        AiPolymarketDecision decision = buyDecision().setEstimatedEdge(new BigDecimal("0.04"));
        PolymarketOrderResult result = executor.execute(decision, context());

        assertEquals("SKIPPED", result.getStatus());
        assertEquals("estimatedEdge below configured minimum", result.getSkipReason());
    }

    @Test
    void skipsWhenWinConfidenceScoreIsTooSmall() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.setMinWinConfidenceScore(new BigDecimal("0.50"));
        PolymarketOrderExecutor executor = new PolymarketOrderExecutor(properties, new RecordingRunner(), null);

        AiPolymarketDecision decision = buyDecision()
                .setWinProbability(new BigDecimal("0.60"))
                .setConfidence(new BigDecimal("0.70"));
        PolymarketOrderResult result = executor.execute(decision, context());

        assertEquals("SKIPPED", result.getStatus());
        assertEquals("winProbability * confidence below configured minimum", result.getSkipReason());
    }

    @Test
    void skipsWhenMarketResolvesBeyondTurnoverWindow() {
        AiPolymarketProperties properties = new AiPolymarketProperties();
        properties.setEnabled(true);
        properties.setMinWinConfidenceScore(new BigDecimal("0.45"));
        properties.setRequireMarketEndDate(true);
        properties.setMaxTimeToResolutionHours(72);
        PolymarketOrderExecutor executor = new PolymarketOrderExecutor(properties, new RecordingRunner(), null);

        PolymarketOrderResult result = executor.execute(
                buyDecision(),
                contextWithEndDate(Instant.now().plus(Duration.ofDays(7)).toString())
        );

        assertEquals("SKIPPED", result.getStatus());
        assertEquals("market resolves beyond configured short-term window", result.getSkipReason());
    }

    @Test
    void finalOrderCheckAlsoRequiresConfirmedMarketAndBookStatus() {
        var runner = mock(PolymarketOrderRunner.class);
        var geoblock = mock(PolymarketGeoblockService.class);
        var executor = new PolymarketOrderExecutor(liveProperties(), runner, geoblock);
        var context = context();
        var market = context.getMarkets().getFirst();

        market.setAcceptingOrders(null);
        assertEquals("SKIPPED", executor.execute(buyDecision(), context).getStatus());
        market.setAcceptingOrders(true).setEnableOrderBook(null);
        assertEquals("SKIPPED", executor.execute(buyDecision(), context).getStatus());
        market.setEnableOrderBook(true).setActive(false);
        assertEquals("SKIPPED", executor.execute(buyDecision(), context).getStatus());
        verifyNoInteractions(geoblock, runner);
    }

    private static AiPolymarketDecision buyDecision() {
        return new AiPolymarketDecision()
                .setAction(PolymarketAction.BUY)
                .setReason("edge")
                .setTokenId("token-1")
                .setOutcome("Yes")
                .setLimitPrice(new BigDecimal("0.50"))
                .setMaxSpendUsdc(new BigDecimal("8"))
                .setWinProbability(new BigDecimal("0.60"))
                .setConfidence(new BigDecimal("0.75"))
                .setEstimatedProbability(new BigDecimal("0.60"))
                .setEstimatedEdge(new BigDecimal("0.10"));
    }

    private static PolymarketDecisionContext context() {
        return contextWithEndDate(null);
    }

    private static PolymarketDecisionContext contextWithEndDate(String endDate) {
        PolymarketOutcomeSnapshot outcome = new PolymarketOutcomeSnapshot()
                .setOutcome("Yes")
                .setTokenId("token-1")
                .setMinOrderSize("1");
        PolymarketMarketSnapshot market = new PolymarketMarketSnapshot()
                .setSlug("market")
                .setQuestion("Question?")
                .setEndDate(endDate)
                .setAcceptingOrders(true)
                .setEnableOrderBook(true)
                .setOutcomes(List.of(outcome));
        return new PolymarketDecisionContext().setMarkets(List.of(market));
    }

    private static class RecordingRunner implements PolymarketOrderRunner {
        private boolean called;

        @Override
        public String placeOrder(PolymarketOrderRequest request) {
            called = true;
            return "{}";
        }
    }
}
