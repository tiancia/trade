package com.trade.trading.risk;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.model.TradingState;
import com.trade.trading.order.TradingOrderRepository;
import com.trade.trading.persistence.TradingStateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundSafetyServiceTest {
    @Test
    void haltIsPersistedBeforeExchangeCancellationAndBlocksNewOrders() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(TradingProperties.ExecutionMode.LIVE);
        // Closing the new-order gate must not disable emergency cancellation.
        properties.setLiveEnabled(false);
        FundSafetyRepository repository = mock(FundSafetyRepository.class);
        TradingOrderRepository orderRepository = mock(TradingOrderRepository.class);
        TradingStateRepository stateRepository = mock(TradingStateRepository.class);
        OkxApi okxApi = mock(OkxApi.class);
        FundSafetyState halted = new FundSafetyState()
                .setAccountScope("live")
                .setStatus(FundSafetyStatus.HALTED)
                .setReason("operator stop")
                .setVersion(1)
                .setUpdatedAt(Instant.now());
        when(repository.halt(anyString(), anyString(), anyString(), any())).thenReturn(halted);
        when(repository.getOrCreate("live")).thenReturn(halted);
        when(repository.recordActionError("live", null)).thenReturn(halted);
        when(okxApi.cancelAllAfter(any())).thenReturn(OkxResponse.success(List.of()));
        when(okxApi.getPendingOrders(any())).thenReturn(OkxResponse.success(List.of()));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FundSafetyService service = new FundSafetyService(
                repository,
                orderRepository,
                stateRepository,
                okxApi,
                properties,
                meterRegistry
        );

        service.halt("operator-api", "operator stop");

        InOrder ordered = inOrder(repository, okxApi);
        ordered.verify(repository).halt(anyString(), anyString(), anyString(), any());
        ordered.verify(okxApi).cancelAllAfter(any());
        assertThrows(FundSafetyService.TradingFundsHaltedException.class, service::requireActive);
        assertEquals(1.0, meterRegistry.get("trade.trading.fund_safety.status").gauge().value());
        verify(okxApi).getPendingOrders(any());
    }

    @Test
    void liveResumeRequiresASuccessfulReconciliationAfterTheStop() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(TradingProperties.ExecutionMode.LIVE);
        properties.setLiveEnabled(false);
        FundSafetyRepository repository = mock(FundSafetyRepository.class);
        TradingOrderRepository orderRepository = mock(TradingOrderRepository.class);
        TradingStateRepository stateRepository = mock(TradingStateRepository.class);
        OkxApi okxApi = mock(OkxApi.class);
        Instant haltedAt = Instant.now().minusSeconds(60);
        FundSafetyState halted = new FundSafetyState()
                .setAccountScope("live")
                .setStatus(FundSafetyStatus.HALTED)
                .setHaltedAt(haltedAt)
                .setVersion(4);
        FundSafetyState active = new FundSafetyState()
                .setAccountScope("live")
                .setStatus(FundSafetyStatus.ACTIVE)
                .setVersion(5);
        when(repository.getOrCreate("live")).thenReturn(halted);
        when(repository.resume(eq("live"), eq(4L), eq("operator checked"), any())).thenReturn(active);
        when(orderRepository.findReconciliationCandidates(anyString(), anyInt()))
                .thenReturn(List.of());
        when(okxApi.getPendingOrders(any())).thenReturn(OkxResponse.success(List.of()));
        when(okxApi.cancelAllAfter(any())).thenReturn(OkxResponse.success(List.of()));
        when(stateRepository.getState()).thenReturn(new TradingState()
                .setRiskState(new TradingRiskState()));
        FundSafetyService service = new FundSafetyService(
                repository,
                orderRepository,
                stateRepository,
                okxApi,
                properties,
                new SimpleMeterRegistry()
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.resume(4, "operator checked", "RESUME_LIVE_TRADING")
        );

        Instant reconciledAt = Instant.now();
        when(stateRepository.getState()).thenReturn(new TradingState()
                .setPositionLastReconciledAt(reconciledAt.toString())
                .setRiskState(new TradingRiskState()
                        .setLastReconciliationAt(reconciledAt.toString())
                        .setConsecutiveReconciliationFailures(0)));

        assertEquals(
                FundSafetyStatus.ACTIVE,
                service.resume(4, "operator checked", "RESUME_LIVE_TRADING").getStatus()
        );
        verify(okxApi).cancelAllAfter(any());
    }
}
