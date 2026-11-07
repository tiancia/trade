package com.trade.trading.application.order;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PositionResp;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.domain.risk.FundSafetyPolicy;
import com.trade.trading.domain.risk.FundSafetyState;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.config.TradingRiskConfiguration;
import com.trade.trading.infrastructure.persistence.TradingStateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

class OrderReconciliationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void unresolvedOrderIsSettledAndAppliedToPositionWithoutResubmission() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        // Recovery must remain available while the new-order gate is closed.
        properties.setLiveEnabled(false);
        properties.getRisk().setEnabled(false);
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        OrderLifecycleService lifecycle = new OrderLifecycleService(orders, new SimpleMeterRegistry());
        OrderSubmission submission = new OrderSubmission(
                "reconcile-key",
                "stbu-reconcile",
                "decision-1",
                "strategy-1",
                "BTC-USDT",
                "BUY",
                "buy",
                "cash",
                "market",
                "quote_ccy",
                new BigDecimal("50")
        );
        lifecycle.reserve(submission);

        TradingStateRepository stateRepository = new TradingStateRepository(
                tempDir.resolve("state.json")
        );
        RiskControlService riskService = new RiskControlService(properties, stateRepository, Clock.systemUTC(),
                new TradingRiskConfiguration().tradingRiskRules());
        OrderSettlementService settlement = new OrderSettlementService(
                lifecycle,
                stateRepository,
                riskService,
                properties
        );
        OkxApi okxApi = mock(OkxApi.class);
        OrderInfoResp filled = new OrderInfoResp();
        filled.setOrdId("exchange-1");
        filled.setClOrdId("stbu-reconcile");
        filled.setInstId("BTC-USDT");
        filled.setState("filled");
        filled.setAccFillSz("0.001");
        filled.setAvgPx("50000");
        filled.setFee("0");
        when(okxApi.getOrder(any(OrderQueryReq.class))).thenReturn(OkxResponse.success(List.of(filled)));
        AccountBalanceResp balance = new AccountBalanceResp();
        BalanceDetail base = new BalanceDetail();
        base.setCcy("BTC");
        base.setCashBal("0.001");
        balance.setDetails(List.of(base));
        when(okxApi.getAccountBalance(any())).thenReturn(OkxResponse.success(List.of(balance)));

        OrderReconciliationService service = new OrderReconciliationService(
                okxApi,
                orders,
                settlement,
                stateRepository,
                mock(FundSafetyService.class),
                properties,
                new SimpleMeterRegistry()
        );

        service.reconcileOnce();

        assertEquals(OrderStatus.FILLED, lifecycle.find("reconcile-key").orElseThrow().getStatus());
        assertEquals(0, new BigDecimal("0.001").compareTo(
                stateRepository.getState().getTrackedBaseAmount()
        ));
        assertEquals(1, service.status().getLastReconciledCount());
        assertEquals(0, service.status().getConsecutiveFailures());
    }

    @Test
    void repeatedNotFoundKeepsUnknownIdentityAndHaltsInsteadOfAssumingOrderWasNeverAccepted() {
        Fixture fixture = fixture("unknown");
        fixture.lifecycle().markSubmitUnknown("unknown", "submit timeout");
        when(fixture.okx().getOrder(any())).thenReturn(OkxResponse.fail("51603", "Order does not exist"));

        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);
        }

        assertEquals(OrderStatus.SUBMIT_UNKNOWN, fixture.lifecycle().find("unknown").orElseThrow().getStatus());
        verify(fixture.safety()).halt(org.mockito.ArgumentMatchers.eq("order-reconciliation"), any());
        verify(fixture.okx(), never()).placeOrder(any());
        verify(fixture.okx(), never()).getAccountBalance(any());
    }

    @Test
    void failedSettlementDoesNotImportBalanceAheadOfItsFillCheckpoint() {
        Fixture fixture = fixture("failed-settlement");
        doThrow(new IllegalStateException("transient settlement failure")).doCallRealMethod()
                .when(fixture.settlement()).applyExchangeSnapshot(any(), any());
        when(fixture.okx().getOrder(any())).thenReturn(OkxResponse.success(List.of(exchange("filled", "0.001"))));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        verify(fixture.okx(), never()).getAccountBalance(any());
        assertDecimal("0", fixture.state().getState().getTrackedBaseAmount());
        assertEquals(OrderStatus.SUBMITTING, fixture.lifecycle().find("failed-settlement").orElseThrow().getStatus());

        fixture.service().reconcileOnce();
        fixture.service().reconcileOnce();

        assertDecimal("0.001", fixture.state().getState().getTrackedBaseAmount());
        assertEquals(0, fixture.service().status().getConsecutiveFailures());
    }

    @Test
    void workingPartialOrderDefersBalanceComparisonUntilItsFinalFillIsCheckpointed() {
        Fixture fixture = fixture("partial-order");
        when(fixture.okx().getOrder(any())).thenReturn(
                OkxResponse.success(List.of(exchange("partially_filled", "0.0005"))),
                OkxResponse.success(List.of(exchange("filled", "0.001"))));

        fixture.service().reconcileOnce();

        verify(fixture.okx(), never()).getAccountBalance(any());
        assertDecimal("0.0005", fixture.state().getState().getTrackedBaseAmount());
        assertNull(fixture.state().getState().getRiskState().getLastReconciliationAt());

        fixture.service().reconcileOnce();

        assertDecimal("0.001", fixture.state().getState().getTrackedBaseAmount());
        assertEquals(OrderStatus.FILLED, fixture.lifecycle().find("partial-order").orElseThrow().getStatus());
        assertEquals(0, fixture.service().status().getConsecutiveFailures());
    }

    @Test
    void dedicatedSpotBalanceMismatchNeverOverwritesManagedOwnership() {
        Fixture fixture = fixture(null);

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertDecimal("0", fixture.state().getState().getTrackedBaseAmount());
        assertDecimal("0.001", fixture.state().getState().getExchangeBaseAmount());
        verify(fixture.safety()).halt(org.mockito.ArgumentMatchers.eq("position-reconciliation"), any());
    }

    @Test
    void malformedFilledOrderRemainsUnresolvedAndDoesNotPassReconciliation() {
        Fixture fixture = fixture("missing-total");
        when(fixture.okx().getOrder(any())).thenReturn(OkxResponse.success(List.of(exchange("filled", "0"))));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertEquals(OrderStatus.SUBMITTING, fixture.lifecycle().find("missing-total").orElseThrow().getStatus());
        assertDecimal("0", fixture.state().getState().getTrackedBaseAmount());
        verify(fixture.okx(), never()).getAccountBalance(any());
    }

    @Test
    void missingAccountDataCannotBeRecordedAsSuccessfulPositionReconciliation() {
        Fixture fixture = fixture(null);
        when(fixture.okx().getAccountBalance(any())).thenReturn(OkxResponse.success(List.of()));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertNull(fixture.state().getState().getPositionLastReconciledAt());
        assertEquals(1, fixture.service().status().getConsecutiveFailures());
    }

    @Test
    void incompleteCancellationStaysUnresolvedUntilItsPartialFillCanBeSettled() {
        Fixture fixture = fixture("cancel-missing-total");
        when(fixture.okx().getOrder(any())).thenReturn(OkxResponse.success(List.of(exchange("canceled", null))));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertEquals(OrderStatus.SUBMITTING, fixture.lifecycle().find("cancel-missing-total").orElseThrow().getStatus());
        assertDecimal("0", fixture.state().getState().getTrackedBaseAmount());
        verify(fixture.okx(), never()).getAccountBalance(any());
        verify(fixture.okx(), never()).placeOrder(any());

        when(fixture.okx().getOrder(any())).thenReturn(OkxResponse.success(List.of(exchange("canceled", "0.001"))));
        fixture.service().reconcileOnce();

        assertEquals(OrderStatus.CANCELED, fixture.lifecycle().find("cancel-missing-total").orElseThrow().getStatus());
        assertDecimal("0.001", fixture.state().getState().getTrackedBaseAmount());
        assertEquals(0, fixture.service().status().getConsecutiveFailures());
        verify(fixture.okx(), never()).placeOrder(any());
    }

    @Test
    void unrelatedSnapshotCannotResolveAnUnknownSubmitOrImportItsBalance() {
        Fixture fixture = fixture("unknown-identity");
        fixture.lifecycle().markSubmitUnknown("unknown-identity", "timeout");
        OrderInfoResp unrelated = exchange("filled", "0.001");
        unrelated.setClOrdId(null);
        when(fixture.okx().getOrder(any())).thenReturn(OkxResponse.success(List.of(unrelated)));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertEquals(OrderStatus.SUBMIT_UNKNOWN, fixture.lifecycle().find("unknown-identity").orElseThrow().getStatus());
        assertDecimal("0", fixture.state().getState().getTrackedBaseAmount());
        verify(fixture.okx(), never()).getAccountBalance(any());
        verify(fixture.okx(), never()).placeOrder(any());
    }

    @Test
    void missingBalanceDetailsCannotBeRecordedAsSuccessfulPositionReconciliation() {
        Fixture fixture = fixture(null);
        when(fixture.okx().getAccountBalance(any()))
                .thenReturn(OkxResponse.success(List.of(new AccountBalanceResp())));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertNull(fixture.state().getState().getPositionLastReconciledAt());
        assertEquals(1, fixture.service().status().getConsecutiveFailures());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "NaN", "-1"})
    void invalidSpotBalanceCannotBecomeVerifiedZero(String cashBalance) {
        Fixture fixture = fixture(null);
        when(fixture.okx().getAccountBalance(any())).thenReturn(balance(cashBalance, null));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertNull(fixture.state().getState().getPositionLastReconciledAt());
        assertEquals(1, fixture.service().status().getConsecutiveFailures());
        verify(fixture.okx(), never()).placeOrder(any());
    }

    @Test
    void ambiguousBalanceSnapshotsCannotPassReconciliation() {
        var validAccount = balance("0", null).getData().getFirst();
        var duplicateCurrency = balance("0", null).getData().getFirst();
        duplicateCurrency.setDetails(List.of(validAccount.getDetails().getFirst(), validAccount.getDetails().getFirst()));
        for (var response : List.of(OkxResponse.success(List.of(validAccount, validAccount)),
                OkxResponse.success(List.of(duplicateCurrency)))) {
            Fixture fixture = fixture(null);
            when(fixture.okx().getAccountBalance(any())).thenReturn(response);

            assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

            assertNull(fixture.state().getState().getPositionLastReconciledAt());
        }
    }

    @Test
    void explicitZeroEmptyCurrencyListAndEquityFallbackRemainSupported() {
        AccountBalanceResp emptyAccount = new AccountBalanceResp();
        emptyAccount.setDetails(List.of());
        for (var response : List.of(balance("0", null), balance(null, "0"),
                OkxResponse.success(List.of(emptyAccount)))) {
            Fixture fixture = fixture(null);
            when(fixture.okx().getAccountBalance(any())).thenReturn(response);

            assertDoesNotThrow(fixture.service()::reconcileOnce);

            assertEquals(0, fixture.service().status().getConsecutiveFailures());
            assertDecimal("0", fixture.state().getState().getExchangeBaseAmount());
        }
    }

    @Test
    void missingDerivativePositionDataCannotEraseManagedPosition() {
        Fixture fixture = fixture(null);
        fixture.properties().setInstType("SWAP");
        fixture.state().recordBuy(new BigDecimal("1"), new BigDecimal("50000"));
        when(fixture.okx().getPositions(any())).thenReturn(OkxResponse.success(null));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertNull(fixture.state().getState().getPositionLastReconciledAt());
        assertDecimal("1", fixture.state().getState().getTrackedBaseAmount());
        assertDecimal("50000", fixture.state().getState().getAverageCost());
        assertEquals(1, fixture.service().status().getConsecutiveFailures());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "0", "-1"})
    void nonzeroDerivativePositionRequiresVerifiedAverageCost(String price) {
        Fixture fixture = fixture(null);
        fixture.properties().setInstType("SWAP");
        fixture.state().recordBuy(BigDecimal.ONE, new BigDecimal("50000"));
        PositionResp position = new PositionResp();
        position.setInstId("BTC-USDT");
        position.setPos("1");
        position.setAvgPx(price);
        when(fixture.okx().getPositions(any())).thenReturn(OkxResponse.success(List.of(position)));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertNull(fixture.state().getState().getPositionLastReconciledAt());
        assertDecimal("50000", fixture.state().getState().getAverageCost());
    }

    @Test
    void emptyAndExplicitZeroDerivativePositionsRemainSupported() {
        PositionResp zero = new PositionResp();
        zero.setInstId("BTC-USDT");
        zero.setPos("0");
        for (List<PositionResp> positions : List.of(List.<PositionResp>of(), List.of(zero))) {
            Fixture fixture = fixture(null);
            fixture.properties().setInstType("SWAP");
            when(fixture.okx().getPositions(any())).thenReturn(OkxResponse.success(positions));

            assertDoesNotThrow(fixture.service()::reconcileOnce);

            assertEquals(0, fixture.service().status().getConsecutiveFailures());
            assertDecimal("0", fixture.state().getState().getExchangeBaseAmount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "-1"})
    void validLongAndShortDerivativeQuantitiesKeepTheirAbsoluteExposure(String quantity) {
        Fixture fixture = fixture(null);
        fixture.properties().setInstType("SWAP");
        fixture.state().recordBuy(BigDecimal.ONE, new BigDecimal("50000"));
        PositionResp position = new PositionResp();
        position.setInstId("BTC-USDT");
        position.setPos(quantity);
        position.setAvgPx("50000");
        when(fixture.okx().getPositions(any())).thenReturn(OkxResponse.success(List.of(position)));

        assertDoesNotThrow(fixture.service()::reconcileOnce);

        assertDecimal("1", fixture.state().getState().getExchangeBaseAmount());
        assertDecimal("50000", fixture.state().getState().getAverageCost());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-fee", "live-with-fill", "fee-without-fill"})
    void invalidFillDetailsDoNotRefreshSuccessfulReconciliation(String shape) {
        Fixture fixture = fixture("invalid-fill");
        var observed = exchange("filled", "0.001");
        switch (shape) {
            case "missing-fee" -> observed.setFee(null);
            case "live-with-fill" -> observed.setState("live");
            default -> {
                observed.setState("canceled");
                observed.setAccFillSz("0");
                observed.setFee("-1");
            }
        }
        when(fixture.okx().getOrder(any())).thenReturn(OkxResponse.success(List.of(observed)));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertEquals(OrderStatus.SUBMITTING, fixture.lifecycle().find("invalid-fill").orElseThrow().getStatus());
        assertEquals(1, fixture.service().status().getConsecutiveFailures());
        assertNull(fixture.state().getState().getPositionLastReconciledAt());
        assertDecimal("0", fixture.state().getState().getTrackedBaseAmount());
        verify(fixture.okx(), never()).getAccountBalance(any());
        verify(fixture.okx(), never()).placeOrder(any());
    }

    @Test
    void duplicateOrderSnapshotsLeaveTheOrderUnresolved() {
        Fixture fixture = fixture("duplicate-response");
        var filled = exchange("filled", "0.001");
        when(fixture.okx().getOrder(any())).thenReturn(OkxResponse.success(List.of(filled, filled)));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertEquals(OrderStatus.SUBMITTING, fixture.lifecycle().find("duplicate-response").orElseThrow().getStatus());
        verify(fixture.okx(), never()).getAccountBalance(any());
        verify(fixture.okx(), never()).placeOrder(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "NaN"})
    void invalidDerivativeQuantityCannotBecomeVerifiedZero(String quantity) {
        Fixture fixture = fixture(null);
        fixture.properties().setInstType("SWAP");
        PositionResp position = new PositionResp();
        position.setInstId("BTC-USDT");
        position.setPos(quantity);
        when(fixture.okx().getPositions(any())).thenReturn(OkxResponse.success(List.of(position)));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertNull(fixture.state().getState().getPositionLastReconciledAt());
        assertEquals(1, fixture.service().status().getConsecutiveFailures());
    }

    @Test
    void reconciliationStartedBeforeHaltCannotAuthorizeResumeAfterItsResponseArrives() {
        Fixture fixture = fixture(null);
        Instant startedAt = Instant.parse("2026-11-04T00:00:00Z");
        Instant haltedAt = startedAt.plusSeconds(1);
        Instant completedAt = startedAt.plusSeconds(2);
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(startedAt);
            when(fixture.okx().getAccountBalance(any())).thenAnswer(invocation -> {
                // The snapshot was requested before the stop and returned two seconds later.
                time.when(Instant::now).thenReturn(completedAt);
                return balance("0", null);
            });
            fixture.service().reconcileOnce();
        }

        var state = fixture.state().getState();
        assertThrows(IllegalStateException.class, () -> FundSafetyPolicy.requireFreshSuccessfulReconciliation(
                new FundSafetyState().setHaltedAt(haltedAt), state));
        assertEquals(fixture.service().status().getLastStartedAt(), Instant.parse(state.getPositionLastReconciledAt()));
        assertEquals(fixture.service().status().getLastStartedAt(), Instant.parse(state.getRiskState().getLastReconciliationAt()));
        assertThrows(IllegalStateException.class, () -> FundSafetyPolicy.requireFreshSuccessfulReconciliation(
                new FundSafetyState().setHaltedAt(startedAt), state));
        assertDoesNotThrow(() -> FundSafetyPolicy.requireFreshSuccessfulReconciliation(
                new FundSafetyState().setHaltedAt(Instant.EPOCH), state));
    }

    private static OkxResponse<AccountBalanceResp> balance(String cash, String equity) {
        BalanceDetail detail = new BalanceDetail();
        detail.setCcy("BTC");
        detail.setCashBal(cash);
        detail.setEq(equity);
        AccountBalanceResp account = new AccountBalanceResp();
        account.setDetails(List.of(detail));
        return OkxResponse.success(List.of(account));
    }

    private Fixture fixture(String key) {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        properties.getRisk().setEnabled(false);
        properties.getReconciliation().setPositionOwnership(TradingProperties.PositionOwnership.DEDICATED_ACCOUNT);
        InMemoryTradingOrderRepository orders = new InMemoryTradingOrderRepository();
        OrderLifecycleService lifecycle = new OrderLifecycleService(orders, new SimpleMeterRegistry());
        if (key != null) {
            lifecycle.reserve(new OrderSubmission(key, "client", "decision", "strategy", "BTC-USDT", "BUY",
                    "buy", "cash", "market", "quote_ccy", new BigDecimal("50")));
        }
        TradingStateRepository state = new TradingStateRepository(tempDir.resolve("fixture.json"));
        RiskControlService risk = new RiskControlService(properties, state, Clock.systemUTC(),
                new TradingRiskConfiguration().tradingRiskRules());
        OrderSettlementService settlement = spy(new OrderSettlementService(lifecycle, state, risk, properties));
        OkxApi okx = mock(OkxApi.class);
        AccountBalanceResp balance = new AccountBalanceResp();
        BalanceDetail base = new BalanceDetail();
        base.setCcy("BTC");
        base.setCashBal("0.001");
        balance.setDetails(List.of(base));
        when(okx.getAccountBalance(any())).thenReturn(OkxResponse.success(List.of(balance)));
        FundSafetyService safety = mock(FundSafetyService.class);
        OrderReconciliationService service = new OrderReconciliationService(okx, orders, settlement,
                state, safety, properties, new SimpleMeterRegistry());
        return new Fixture(okx, lifecycle, state, settlement, service, safety, properties);
    }

    private static OrderInfoResp exchange(String state, String cumulativeSize) {
        OrderInfoResp order = new OrderInfoResp();
        order.setOrdId("exchange");
        order.setClOrdId("client");
        order.setInstId("BTC-USDT");
        order.setSide("buy");
        order.setState(state);
        order.setAccFillSz(cumulativeSize);
        order.setAvgPx("50000");
        order.setFee("0");
        return order;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private record Fixture(OkxApi okx, OrderLifecycleService lifecycle, TradingStateRepository state,
                           OrderSettlementService settlement, OrderReconciliationService service, FundSafetyService safety,
                           TradingProperties properties) {}
}
