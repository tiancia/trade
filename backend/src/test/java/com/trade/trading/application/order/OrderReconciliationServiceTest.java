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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
