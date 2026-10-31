package com.trade.trading.application.execution;

import com.trade.trading.infrastructure.broker.OkxOrderGateway;
import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.application.execution.OrderSizingService;
import com.trade.trading.application.order.InMemoryTradingOrderRepository;
import com.trade.trading.application.order.OrderIdempotencyKeyFactory;
import com.trade.trading.application.order.OrderLifecycleService;
import com.trade.trading.application.order.OrderSettlementService;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.config.TradingRiskConfiguration;
import com.trade.trading.infrastructure.persistence.TradingStateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;

class LiveOrderExecutionServiceTest {
    @TempDir
    Path tempDir;

    @Test void ordinaryDrawdownBlocksBuyingWithoutLatchingTheAllOrdersStopAndSellingStillWorks() {
        TradingProperties properties = liveProperties();
        properties.getRisk().setEnabled(true);
        properties.getRisk().setMaxDrawdownRatio(new BigDecimal("0.05"));
        properties.getRisk().setMaxDailyLossRatio(BigDecimal.ONE);
        var repository = new TradingStateRepository(tempDir.resolve("drawdown.json"));
        repository.recordBuy(new BigDecimal("0.01"), new BigDecimal("50000"));
        repository.recordRiskState(new com.trade.trading.domain.model.TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000")).setEquityHighWatermark(new BigDecimal("1000"))
                .setDayStartEquity(new BigDecimal("1000")));
        var api = new FakeOkxApi(filledOrder("sell", "0.01", "50000"));
        var safety = mock(FundSafetyService.class);
        var execution = broker(properties, api, new InMemoryTradingOrderRepository(), safety,
                mock(TradingLeadershipService.class), repository);
        var context = context("0.01", "400");
        var blocked = new TradingDecisionRecord();
        execution.execute(buyDecision(), context, blocked);
        assertEquals("SKIPPED", blocked.getExecutionStatus());
        assertTrue(blocked.getSkipReason().startsWith("RISK_MAX_DRAWDOWN"));
        org.mockito.Mockito.verify(safety, org.mockito.Mockito.never()).halt(any(), any());
        var sold = new TradingDecisionRecord().setDecisionId("exit-after-daily-loss");
        execution.execute(sellDecision("0.01"), context, sold);
        assertEquals("FILLED", sold.getExecutionStatus());
        assertEquals("sell", api.orderReq.getSide());
    }

    @Test
    void liveBrokerRequiresExplicitDoubleEnableBeforeOkxOrder() {
        TradingProperties properties = properties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, okxApi).execute(buyDecision(), context("0", "1000"), record);

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
    }

    @Test
    void liveBrokerPlacesSpotMarketQuoteOrderWhenEnabled() {
        TradingProperties properties = properties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        properties.setLiveEnabled(true);
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, okxApi).execute(buyDecision(), context("0", "1000"), record);

        assertEquals("BTC-USDT", okxApi.orderReq.getInstId());
        assertEquals("cash", okxApi.orderReq.getTdMode());
        assertEquals("buy", okxApi.orderReq.getSide());
        assertEquals("market", okxApi.orderReq.getOrdType());
        assertEquals("quote_ccy", okxApi.orderReq.getTgtCcy());
        assertEquals("100", okxApi.orderReq.getSz());
        assertEquals("FILLED", record.getExecutionStatus());
        assertEquals(OrderStatus.FILLED.name(), record.getOrderStatus());
        assertEquals(32, record.getClientOrderId().length());
    }

    @Test
    void liveDerivativeOrderFailsClosedUntilItsFinancialLedgerIsImplemented() {
        TradingProperties properties = liveProperties();
        properties.setInstType("SWAP");
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "1", "50000"));
        TradingDecisionRecord record = new TradingDecisionRecord();
        StrategyDecision decision = new StrategyDecision()
                .setStrategyId("test")
                .setAction(TradingAction.OPEN_LONG)
                .setOrderSize(BigDecimal.ONE);

        broker(properties, okxApi).execute(decision, context("0", "1000"), record);

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertTrue(record.getSkipReason().startsWith("Live derivative order blocked"));
    }

    @Test
    void sameBusinessOrderIsSubmittedOnlyOnce() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        LiveOrderExecutionService broker = broker(properties, okxApi, orders);
        TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("same-decision");
        TradingDecisionRecord replay = new TradingDecisionRecord().setDecisionId("same-decision");

        broker.execute(buyDecision(), context("0", "1000"), first);
        broker.execute(buyDecision(), context("0", "1000"), replay);

        assertEquals(1, okxApi.placeOrderCount);
        assertEquals(first.getIdempotencyKey(), replay.getIdempotencyKey());
        assertEquals(first.getClientOrderId(), replay.getClientOrderId());
        assertTrue(replay.isIdempotentReplay());
        assertEquals(OrderStatus.FILLED.name(), replay.getOrderStatus());
        assertEquals(first.getOrderSize(), replay.getOrderSize());
    }

    @Test
    void incompleteAndTimedOutAcknowledgementsStayUnknownAndBlockTheNextSignal() {
        List<OkxResponse<OrderActionResp>> responses = new ArrayList<>();
        responses.add(null);
        responses.add(OkxResponse.success(List.of()));
        responses.add(new OkxResponse<>());
        responses.add(OkxResponse.fail("50004", "endpoint timeout"));
        responses.add(OkxResponse.fail("50026", "system error"));
        OrderActionResp missingOrderId = new OrderActionResp();
        missingOrderId.setSCode("0");
        responses.add(OkxResponse.success(List.of(missingOrderId)));
        OrderActionResp differentClientId = new OrderActionResp();
        differentClientId.setSCode("0");
        differentClientId.setOrdId("exchange");
        differentClientId.setClOrdId("other-order");
        responses.add(OkxResponse.success(List.of(differentClientId)));

        for (OkxResponse<OrderActionResp> response : responses) {
            OkxApi okx = mock(OkxApi.class);
            when(okx.placeOrder(any())).thenReturn(response);
            InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
            LiveOrderExecutionService execution = broker(liveProperties(), okx, orders);
            TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("uncertain-submit");

            assertThrows(IllegalStateException.class,
                    () -> execution.execute(buyDecision(), context("0", "1000"), first));

            assertEquals(OrderStatus.SUBMIT_UNKNOWN.name(), first.getOrderStatus());
            TradingDecisionRecord next = new TradingDecisionRecord().setDecisionId("next-signal");
            execution.execute(buyDecision(), context("0", "1000"), next);
            assertEquals("SKIPPED", next.getExecutionStatus());
            verify(okx, times(1)).placeOrder(any());
        }
    }

    @Test
    void explicitBusinessRejectionRemainsTerminal() {
        OkxApi okx = mock(OkxApi.class);
        when(okx.placeOrder(any())).thenReturn(OkxResponse.fail("51008", "insufficient balance"));
        TradingDecisionRecord record = new TradingDecisionRecord().setDecisionId("rejected-submit");

        assertThrows(com.trade.trading.port.ExchangeOrderGateway.OrderRejectedException.class,
                () -> broker(liveProperties(), okx).execute(buyDecision(), context("0", "1000"), record));

        assertEquals(OrderStatus.REJECTED.name(), record.getOrderStatus());
    }

    @Test
    void duplicateClientOrderIdKeepsTheOriginalOrderUnresolved() {
        assertAmbiguousResponseBlocksNextSignal(request ->
                OkxResponse.fail("51016", "Client order ID already exists"));
        assertAmbiguousResponseBlocksNextSignal(request ->
                orderAcknowledgement("1", "51016", request.getClOrdId(), ""));
        assertAmbiguousResponseBlocksNextSignal(request ->
                orderAcknowledgement("1", "51016_1000", request.getClOrdId(), ""));
    }

    @Test
    void malformedRejectionsCannotReleaseTheUnresolvedOrderGate() {
        assertAmbiguousResponseBlocksNextSignal(request ->
                orderAcknowledgement("1", "51008", "another-order", ""));
        assertAmbiguousResponseBlocksNextSignal(request ->
                orderAcknowledgement("1", "51008", null, ""));
        assertAmbiguousResponseBlocksNextSignal(request ->
                orderAcknowledgement("1", null, request.getClOrdId(), ""));
        assertAmbiguousResponseBlocksNextSignal(request ->
                orderAcknowledgement("1", "0", request.getClOrdId(), "accepted-order"));
        assertAmbiguousResponseBlocksNextSignal(request -> OkxResponse.fail("1", "Operation failed"));
        assertAmbiguousResponseBlocksNextSignal(request -> OkxResponse.fail("2", "Partially succeeded"));
        assertAmbiguousResponseBlocksNextSignal(request -> OkxResponse.fail("unknown", "Invalid result"));
        assertAmbiguousResponseBlocksNextSignal(request -> {
            var response = orderAcknowledgement("1", "51008", request.getClOrdId(), "");
            response.setData(List.of(response.getData().getFirst(), response.getData().getFirst()));
            return response;
        });
    }

    @Test
    void matchingItemBusinessRejectionRemainsTerminal() {
        OkxApi okx = mock(OkxApi.class);
        when(okx.placeOrder(any())).thenAnswer(invocation -> {
            PlaceOrderReq request = invocation.getArgument(0);
            return orderAcknowledgement("1", "51008", request.getClOrdId(), "");
        });
        TradingDecisionRecord record = new TradingDecisionRecord().setDecisionId("item-rejected-submit");

        assertThrows(com.trade.trading.port.ExchangeOrderGateway.OrderRejectedException.class,
                () -> broker(liveProperties(), okx).execute(buyDecision(), context("0", "1000"), record));

        assertEquals(OrderStatus.REJECTED.name(), record.getOrderStatus());
    }

    @Test
    void duplicateClientOrderIdCanReconcileTheExistingFillWithoutResubmitting() {
        OkxApi okx = mock(OkxApi.class);
        when(okx.placeOrder(any())).thenReturn(OkxResponse.fail("51016", "Client order ID already exists"));
        when(okx.getOrder(any())).thenAnswer(invocation -> {
            OrderQueryReq query = invocation.getArgument(0);
            OrderInfoResp existing = filledOrder("buy", "0.002", "50000");
            existing.setOrdId("existing-order");
            existing.setClOrdId(query.getClOrdId());
            return OkxResponse.success(List.of(existing));
        });
        var orders = new InMemoryTradingOrderRepository();
        var state = new TradingStateRepository(tempDir.resolve("duplicate-id-reconciliation.json"));
        var execution = broker(liveProperties(), okx, orders,
                mock(FundSafetyService.class), mock(TradingLeadershipService.class), state);
        var first = new TradingDecisionRecord().setDecisionId("duplicate-id");
        assertThrows(IllegalStateException.class,
                () -> execution.execute(buyDecision(), context("0", "1000"), first));

        var replay = new TradingDecisionRecord().setDecisionId("duplicate-id");
        execution.execute(buyDecision(), context("0", "1000"), replay);

        assertEquals(OrderStatus.FILLED.name(), replay.getOrderStatus());
        assertTrue(replay.isIdempotentReplay());
        assertEquals(0, new BigDecimal("0.002").compareTo(state.getState().getTrackedBaseAmount()));
        verify(okx, times(1)).placeOrder(any());
    }

    private void assertAmbiguousResponseBlocksNextSignal(
            java.util.function.Function<PlaceOrderReq, OkxResponse<OrderActionResp>> response) {
        OkxApi okx = mock(OkxApi.class);
        when(okx.placeOrder(any())).thenAnswer(invocation -> response.apply(invocation.getArgument(0)));
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        LiveOrderExecutionService execution = broker(liveProperties(), okx, orders);
        TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("ambiguous-rejection");

        assertThrows(IllegalStateException.class,
                () -> execution.execute(buyDecision(), context("0", "1000"), first));

        assertEquals(OrderStatus.SUBMIT_UNKNOWN.name(), first.getOrderStatus());
        assertEquals(OrderStatus.SUBMIT_UNKNOWN,
                orders.findByIdempotencyKey(first.getIdempotencyKey()).orElseThrow().getStatus());
        TradingDecisionRecord next = new TradingDecisionRecord().setDecisionId("next-signal-after-rejection");
        execution.execute(buyDecision(), context("0", "1000"), next);
        assertEquals("SKIPPED", next.getExecutionStatus());
        verify(okx, times(1)).placeOrder(any());
    }

    private static OkxResponse<OrderActionResp> orderAcknowledgement(
            String code, String itemCode, String clientOrderId, String orderId) {
        OrderActionResp item = new OrderActionResp();
        item.setSCode(itemCode);
        item.setClOrdId(clientOrderId);
        item.setOrdId(orderId);
        var response = OkxResponse.success(List.of(item));
        response.setCode(code);
        return response;
    }

    @Test
    void sameConfirmedSignalCannotSubmitAgainAfterSizeConfigOrFastMarketSnapshotChanges() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        Map<String, Object> signal = Map.of("signalBar", "15m", "signalCandleTs", 1700000000000L);
        TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("first-random").setStrategyConfigVersion(1);
        TradingDecisionRecord replay = new TradingDecisionRecord().setDecisionId("second-random").setStrategyConfigVersion(2);
        TradingDecisionContext firstContext = context("0", "1000");
        firstContext.getTicker().setTs("1700000060000");
        TradingDecisionContext secondContext = context("0", "1000");
        secondContext.getTicker().setTs("1700000120000");

        broker(properties, okxApi, orders).execute(buyDecision().setMetadata(signal), firstContext, first);
        // A fresh execution service uses the same order ledger; sizing also differs.
        broker(properties, okxApi, orders).execute(buyDecision().setBuyQuoteAmount(new BigDecimal("20"))
                .setMetadata(signal), secondContext, replay);

        assertEquals(1, okxApi.placeOrderCount);
        assertEquals(first.getIdempotencyKey(), replay.getIdempotencyKey());
        assertEquals(first.getClientOrderId(), replay.getClientOrderId());
        assertTrue(replay.isIdempotentReplay());
        assertEquals(OrderStatus.FILLED.name(), replay.getOrderStatus());
        assertEquals(first.getOrderSize(), replay.getOrderSize());
    }

    @Test
    void persistentFundStopBlocksLiveSubmission() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        FundSafetyService fundSafetyService = mock(FundSafetyService.class);
        doThrow(new FundSafetyService.TradingFundsHaltedException("Live trading halted: test"))
                .when(fundSafetyService)
                .requireActive();
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(
                properties,
                okxApi,
                new InMemoryTradingOrderRepository(),
                fundSafetyService
        ).execute(buyDecision(), context("0", "1000"), record);

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals("Live trading halted: test", record.getSkipReason());
    }

    @Test
    void liveSubmissionFailsClosedWithoutDatabaseLeadership() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingLeadershipService leadershipService = mock(TradingLeadershipService.class);
        doThrow(new TradingLeadershipService.TradingLeadershipUnavailableException(
                "Live OKX order blocked: trading lease unavailable"
        )).when(leadershipService).requireLiveLeadership();
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(
                properties,
                okxApi,
                new InMemoryTradingOrderRepository(),
                mock(FundSafetyService.class),
                leadershipService
        ).execute(buyDecision(), context("0", "1000"), record);

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertTrue(record.getSkipReason().contains("trading lease unavailable"));
    }

    @Test
    void leadershipLostAfterReservationIsRejectedWithoutAmbiguousSubmit() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        TradingLeadershipService leadershipService = mock(TradingLeadershipService.class);
        doNothing()
                .doThrow(new TradingLeadershipService.TradingLeadershipUnavailableException(
                        "Live OKX order blocked: lease transferred"
                ))
                .when(leadershipService)
                .requireLiveLeadership();
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(
                properties,
                okxApi,
                new InMemoryTradingOrderRepository(),
                mock(FundSafetyService.class),
                leadershipService
        ).execute(buyDecision(), context("0", "1000"), record);

        assertNull(okxApi.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals(OrderStatus.REJECTED.name(), record.getOrderStatus());
        assertTrue(record.getSkipReason().contains("lease transferred"));
    }

    @Test
    void ambiguousSubmitIsReconciledWithoutSecondPlaceOrder() {
        TradingProperties properties = liveProperties();
        FakeOkxApi okxApi = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        okxApi.failNextPlace = true;
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        LiveOrderExecutionService broker = broker(properties, okxApi, orders);
        TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("timeout-decision");

        assertThrows(IllegalStateException.class,
                () -> broker.execute(buyDecision(), context("0", "1000"), first));
        assertEquals(OrderStatus.SUBMIT_UNKNOWN.name(), first.getOrderStatus());

        TradingDecisionRecord replay = new TradingDecisionRecord().setDecisionId("timeout-decision");
        broker.execute(buyDecision(), context("0", "1000"), replay);

        assertEquals(1, okxApi.placeOrderCount);
        assertTrue(replay.isIdempotentReplay());
        assertEquals(OrderStatus.FILLED.name(), replay.getOrderStatus());
    }

    @Test
    void newBuyDecisionCannotSubmitWhileEarlierBuyOutcomeIsUnknown() {
        TradingProperties properties = liveProperties();
        FakeOkxApi api = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        api.failNextPlace = true;
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        LiveOrderExecutionService execution = broker(properties, api, orders);
        TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("first-signal");
        assertThrows(IllegalStateException.class,
                () -> execution.execute(buyDecision(), context("0", "1000"), first));

        TradingDecisionRecord next = new TradingDecisionRecord().setDecisionId("next-signal");
        execution.execute(buyDecision(), context("0", "1000"), next);

        assertEquals(1, api.placeOrderCount);
        assertEquals("SKIPPED", next.getExecutionStatus());
        assertEquals(OrderStatus.REJECTED.name(), next.getOrderStatus());
        assertTrue(next.getSkipReason().contains("reconcile outstanding orders"));
        assertEquals(OrderStatus.SUBMIT_UNKNOWN,
                orders.findByIdempotencyKey(first.getIdempotencyKey()).orElseThrow().getStatus());
    }

    @Test
    void outstandingOrderReadFailureBlocksBuyWithoutAmbiguousSubmit() {
        TradingProperties properties = liveProperties();
        FakeOkxApi api = new FakeOkxApi(filledOrder("buy", "0.002", "50000"));
        InMemoryTradingOrderRepository orders = spy(new InMemoryTradingOrderRepository());
        doThrow(new IllegalStateException("database unavailable"))
                .when(orders).findReconciliationCandidates("BTC-USDT", 2);
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, api, orders).execute(buyDecision(), context("0", "1000"), record);

        assertEquals(0, api.placeOrderCount);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals(OrderStatus.REJECTED.name(), record.getOrderStatus());
        assertTrue(record.getSkipReason().contains("cannot verify outstanding orders"));
    }

    @Test
    void explicitLiveSellCannotUseManualBalanceOrStaleDecisionOwnership() {
        TradingProperties properties = liveProperties();
        FakeOkxApi api = new FakeOkxApi(filledOrder("sell", "1", "50000"));
        TradingDecisionContext context = context("1", "1000")
                .setTradingState(new TradingState().setTrackedBaseAmount(BigDecimal.ONE));
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, api).execute(sellDecision("1"), context, record);

        assertNull(api.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertTrue(record.getSkipReason().contains("no available managed position"));
    }

    @Test
    void liveSellCapsMixedHoldingsToLatestPersistedManagedQuantity() {
        TradingProperties properties = liveProperties();
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("mixed.json"));
        repository.recordBuy(new BigDecimal("0.2"), new BigDecimal("50000"));
        FakeOkxApi api = new FakeOkxApi(filledOrder("sell", "0.2", "50000"));
        TradingDecisionContext context = context("2", "1000")
                .setTradingState(new TradingState().setTrackedBaseAmount(new BigDecimal("2")));
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, api, new InMemoryTradingOrderRepository(), mock(FundSafetyService.class),
                mock(TradingLeadershipService.class), repository).execute(sellDecision("2"), context, record);

        assertEquals("0.2", api.orderReq.getSz());
        assertEquals("FILLED", record.getExecutionStatus());
        assertEquals(0, repository.getState().getTrackedBaseAmount().signum());
    }

    @Test
    void dedicatedPositionCanBeSoldWhenTheFillLedgerOwnsIt() {
        TradingProperties properties = liveProperties();
        properties.getReconciliation().setPositionOwnership(TradingProperties.PositionOwnership.DEDICATED_ACCOUNT);
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("dedicated.json"));
        repository.recordBuy(BigDecimal.ONE, new BigDecimal("50000"));
        repository.recordExchangePosition(BigDecimal.ONE, null, null, java.time.Instant.now());
        FakeOkxApi api = new FakeOkxApi(filledOrder("sell", "1", "50000"));
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, api, new InMemoryTradingOrderRepository(), mock(FundSafetyService.class),
                mock(TradingLeadershipService.class), repository).execute(sellDecision("1"), context("1", "1000"), record);

        assertEquals("1", api.orderReq.getSz());
        assertEquals("FILLED", record.getExecutionStatus());
    }

    @Test
    void managedQuantityLostAfterReservationBlocksSubmissionWithoutUnknownStatus() {
        TradingProperties properties = liveProperties();
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("shrinking.json"));
        repository.recordBuy(new BigDecimal("0.2"), new BigDecimal("50000"));
        FakeOkxApi api = new FakeOkxApi(filledOrder("sell", "0.2", "50000"));
        TradingLeadershipService leadership = mock(TradingLeadershipService.class);
        doNothing().doAnswer(invocation -> { repository.recordSell(new BigDecimal("0.2")); return null; })
                .when(leadership).requireLiveLeadership();
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, api, new InMemoryTradingOrderRepository(), mock(FundSafetyService.class), leadership,
                repository).execute(sellDecision("0.2"), context("2", "1000"), record);

        assertNull(api.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals("REJECTED", record.getOrderStatus());
        assertTrue(record.getSkipReason().contains("changed before submission"));
    }

    @Test
    void ownershipReadFailureAfterReservationIsBlockedRatherThanAmbiguousSubmit() {
        TradingProperties properties = liveProperties();
        TradingStateRepository repository = spy(new TradingStateRepository(tempDir.resolve("unreadable.json")));
        repository.recordBuy(new BigDecimal("0.2"), new BigDecimal("50000"));
        FakeOkxApi api = new FakeOkxApi(filledOrder("sell", "0.2", "50000"));
        TradingLeadershipService leadership = mock(TradingLeadershipService.class);
        doNothing().doAnswer(invocation -> {
            doThrow(new IllegalStateException("database unavailable")).when(repository).getState();
            return null;
        }).when(leadership).requireLiveLeadership();
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, api, new InMemoryTradingOrderRepository(), mock(FundSafetyService.class), leadership,
                repository).execute(sellDecision("0.2"), context("2", "1000"), record);

        assertNull(api.orderReq);
        assertEquals("SKIPPED", record.getExecutionStatus());
        assertEquals("REJECTED", record.getOrderStatus());
        assertTrue(record.getSkipReason().contains("cannot verify"));
    }

    @Test
    void anotherUnsettledOrderPreventsSellingTheSameManagedUnitsAgain() {
        TradingProperties properties = liveProperties();
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("outstanding.json"));
        repository.recordBuy(new BigDecimal("0.2"), new BigDecimal("50000"));
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        OrderLifecycleService lifecycle = new OrderLifecycleService(orders, new SimpleMeterRegistry());
        lifecycle.reserve(new OrderSubmission("earlier", "earlierSell", "earlierDecision", "test", "BTC-USDT",
                "SELL", "sell", "cash", "market", "base_ccy", new BigDecimal("0.2")));
        lifecycle.markAccepted("earlier", "earlierExchangeOrder");
        FakeOkxApi api = new FakeOkxApi(filledOrder("sell", "0.2", "50000"));
        TradingDecisionRecord record = new TradingDecisionRecord();

        broker(properties, api, orders, mock(FundSafetyService.class), mock(TradingLeadershipService.class),
                repository).execute(sellDecision("0.2"), context("2", "1000"), record);

        assertNull(api.orderReq);
        assertEquals("REJECTED", record.getOrderStatus());
        assertTrue(record.getSkipReason().contains("reconcile outstanding orders"));
        assertEquals(OrderStatus.ACCEPTED, lifecycle.find("earlier").orElseThrow().getStatus());
    }

    @Test
    void replayOfUnsettledSellStillQueriesWithoutSecondSubmission() {
        TradingProperties properties = liveProperties();
        TradingStateRepository repository = new TradingStateRepository(tempDir.resolve("replay-sell.json"));
        repository.recordBuy(new BigDecimal("0.2"), new BigDecimal("50000"));
        OrderInfoResp liveOrder = filledOrder("sell", "0", "0");
        liveOrder.setState("live");
        FakeOkxApi api = new FakeOkxApi(liveOrder);
        LiveOrderExecutionService execution = broker(properties, api, new InMemoryTradingOrderRepository(),
                mock(FundSafetyService.class), mock(TradingLeadershipService.class), repository);
        TradingDecisionRecord first = new TradingDecisionRecord().setDecisionId("same-sell");
        TradingDecisionRecord replay = new TradingDecisionRecord().setDecisionId("same-sell");

        execution.execute(sellDecision("0.2"), context("2", "1000"), first);
        execution.execute(sellDecision("0.2"), context("2", "1000"), replay);

        assertEquals(1, api.placeOrderCount);
        assertTrue(replay.isIdempotentReplay());
        assertEquals(first.getIdempotencyKey(), replay.getIdempotencyKey());
    }

    private LiveOrderExecutionService broker(TradingProperties properties, OkxApi okxApi) {
        return broker(properties, okxApi, new InMemoryTradingOrderRepository());
    }

    private LiveOrderExecutionService broker(
            TradingProperties properties,
            OkxApi okxApi,
            InMemoryTradingOrderRepository orders
    ) {
        return broker(properties, okxApi, orders, mock(FundSafetyService.class));
    }

    private LiveOrderExecutionService broker(
            TradingProperties properties,
            OkxApi okxApi,
            InMemoryTradingOrderRepository orders,
            FundSafetyService fundSafetyService
    ) {
        return broker(
                properties,
                okxApi,
                orders,
                fundSafetyService,
                mock(TradingLeadershipService.class)
        );
    }

    private LiveOrderExecutionService broker(
            TradingProperties properties,
            OkxApi okxApi,
            InMemoryTradingOrderRepository orders,
            FundSafetyService fundSafetyService,
            TradingLeadershipService leadershipService
    ) {
        TradingStateRepository stateRepository = new TradingStateRepository(tempDir.resolve("state-" + System.nanoTime() + ".json"));
        return broker(properties, okxApi, orders, fundSafetyService, leadershipService, stateRepository);
    }

    private LiveOrderExecutionService broker(
            TradingProperties properties, OkxApi okxApi, InMemoryTradingOrderRepository orders,
            FundSafetyService fundSafetyService, TradingLeadershipService leadershipService,
            TradingStateRepository stateRepository
    ) {
        RiskControlService riskControlService = new RiskControlService(properties, stateRepository, Clock.systemUTC(),
                new TradingRiskConfiguration().tradingRiskRules());
        OrderLifecycleService lifecycleService = new OrderLifecycleService(orders, new SimpleMeterRegistry());
        return new LiveOrderExecutionService(
                new OkxOrderGateway(okxApi, properties),
                new OrderSizingService(properties),
                properties,
                riskControlService,
                fundSafetyService,
                leadershipService,
                lifecycleService,
                new OrderSettlementService(
                        lifecycleService,
                        stateRepository,
                        riskControlService,
                        properties
                ),
                new OrderIdempotencyKeyFactory(),
                stateRepository
        );
    }

    private static TradingProperties liveProperties() {
        TradingProperties properties = properties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        properties.setLiveEnabled(true);
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        return properties;
    }

    private static TradingProperties properties() {
        TradingProperties properties = new TradingProperties();
        properties.setOrderFillQueryDelayMs(0);
        properties.getRisk().setEnabled(false);
        return properties;
    }

    private static StrategyDecision buyDecision() {
        return new StrategyDecision()
                .setStrategyId("test")
                .setAction(TradingAction.BUY)
                .setReason("test buy")
                .setBuyQuoteAmount(new BigDecimal("500"));
    }

    private static StrategyDecision sellDecision(String amount) {
        return StrategyDecision.sell("test", new BigDecimal(amount), "test sell");
    }

    private static TradingDecisionContext context(String baseAvail, String quoteAvail) {
        TickerResp ticker = new TickerResp();
        ticker.setLast("50000");

        BalanceDetail base = new BalanceDetail();
        base.setCcy("BTC");
        base.setAvailBal(baseAvail);

        BalanceDetail quote = new BalanceDetail();
        quote.setCcy("USDT");
        quote.setAvailBal(quoteAvail);

        InstrumentInfoResp instrument = new InstrumentInfoResp();
        instrument.setMinSz("0.00001");
        instrument.setLotSz("0.0001");
        instrument.setMaxMktAmt("1000");
        instrument.setMaxMktSz("10");

        return new TradingDecisionContext()
                .setTicker(ticker)
                .setBaseBalance(base)
                .setQuoteBalance(quote)
                .setInstrument(instrument);
    }

    private static OrderInfoResp filledOrder(String side, String fillSize, String avgPrice) {
        OrderInfoResp order = new OrderInfoResp();
        order.setSide(side);
        order.setAccFillSz(fillSize);
        order.setAvgPx(avgPrice);
        order.setState("filled");
        return order;
    }

    private static class FakeOkxApi extends OkxApi {
        private final OrderInfoResp orderInfoResp;
        private PlaceOrderReq orderReq;
        private int placeOrderCount;
        private boolean failNextPlace;

        FakeOkxApi(OrderInfoResp orderInfoResp) {
            super(new NoopOkxRestClient());
            this.orderInfoResp = orderInfoResp;
        }

        @Override
        public OkxResponse<OrderActionResp> placeOrder(PlaceOrderReq req) {
            placeOrderCount++;
            this.orderReq = req;
            if (failNextPlace) {
                failNextPlace = false;
                throw new IllegalStateException("simulated response timeout");
            }
            OrderActionResp resp = new OrderActionResp();
            resp.setOrdId("1");
            resp.setClOrdId(req.getClOrdId());
            resp.setSCode("0");
            return OkxResponse.success(List.of(resp));
        }

        @Override
        public OkxResponse<OrderInfoResp> getOrder(OrderQueryReq req) {
            orderInfoResp.setOrdId(req.getOrdId() == null ? "1" : req.getOrdId());
            orderInfoResp.setClOrdId(req.getClOrdId());
            return OkxResponse.success(List.of(orderInfoResp));
        }
    }

    private static class NoopOkxRestClient implements OkxRestClient {
        @Override
        public <T> OkxResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }

        @Override
        public <T> OkxResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }
    }
}
