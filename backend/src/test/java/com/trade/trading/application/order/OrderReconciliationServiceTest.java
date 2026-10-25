package com.trade.trading.application.order;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.domain.model.ExecutionMode;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void missingBalanceDetailsCannotBeRecordedAsSuccessfulPositionReconciliation() {
        Fixture fixture = fixture(null);
        when(fixture.okx().getAccountBalance(any()))
                .thenReturn(OkxResponse.success(List.of(new AccountBalanceResp())));

        assertThrows(IllegalStateException.class, fixture.service()::reconcileOnce);

        assertNull(fixture.state().getState().getPositionLastReconciledAt());
        assertEquals(1, fixture.service().status().getConsecutiveFailures());
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
        return new Fixture(okx, lifecycle, state, settlement, service, safety);
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
        return order;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private record Fixture(OkxApi okx, OrderLifecycleService lifecycle, TradingStateRepository state,
                           OrderSettlementService settlement, OrderReconciliationService service, FundSafetyService safety) {}
}
