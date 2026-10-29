package com.trade.trading.interfaces.web;

import com.trade.trading.application.backtest.BacktestService;
import com.trade.trading.application.order.OrderLifecycleService;
import com.trade.trading.application.order.OrderReconciliationService;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.application.strategy.TradingStrategyEngine;
import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.application.strategy.TradingStrategySelectionService;
import com.trade.trading.domain.risk.FundSafetyState;
import com.trade.trading.domain.risk.FundSafetyStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TradingControllerFundSafetyTest {
    @Test
    void fundStopDoesNotRequireApplicationToken() throws Exception {
        FundSafetyService fundSafetyService = mock(FundSafetyService.class);
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        TradingController controller = controller(fundSafetyService, reconciliationService);

        FundSafetyState halted = new FundSafetyState()
                .setStatus(FundSafetyStatus.HALTED)
                .setReason("incident");
        when(fundSafetyService.halt("operator-api", "incident")).thenReturn(halted);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new TradingExceptionHandler()).build();
        mvc.perform(post("/api/trading/safety/stop").contentType("application/json")
                        .content("{\"reason\":\"incident\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HALTED"));
        verify(fundSafetyService).halt("operator-api", "incident");
    }

    @Test
    void blankStopReasonIsStillRejected() {
        FundSafetyService fundSafetyService = mock(FundSafetyService.class);
        TradingController controller = controller(fundSafetyService, mock(OrderReconciliationService.class));
        ResponseStatusException invalid = assertThrows(ResponseStatusException.class,
                () -> controller.stopFunds(new TradingController.FundStopRequest(" ")));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        verifyNoInteractions(fundSafetyService);
    }

    @Test
    void fundResumePassesVersionAndConfirmationWithoutApplicationToken() throws Exception {
        FundSafetyService fundSafetyService = mock(FundSafetyService.class);
        TradingController controller = controller(fundSafetyService, mock(OrderReconciliationService.class));
        when(fundSafetyService.resume(3L, "reconciled", "RESUME_LIVE_TRADING"))
                .thenReturn(new FundSafetyState().setStatus(FundSafetyStatus.ACTIVE));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new TradingExceptionHandler()).build();
        mvc.perform(post("/api/trading/safety/resume").contentType("application/json")
                        .content("{\"expectedVersion\":3,\"reason\":\"reconciled\",\"confirmation\":\"RESUME_LIVE_TRADING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        verify(fundSafetyService).resume(3L, "reconciled", "RESUME_LIVE_TRADING");
    }

    @Test
    void leaderCanReconcileWithoutApplicationToken() {
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        TradingLeadershipService leadershipService = mock(TradingLeadershipService.class);
        TradingController controller = controller(
                mock(FundSafetyService.class),
                reconciliationService,
                leadershipService
        );
        when(leadershipService.runIfLeader(eq("manual-reconciliation"), any(Runnable.class)))
                .thenAnswer(invocation -> { invocation.<Runnable>getArgument(1).run(); return true; });
        controller.reconcileNow();
        verify(reconciliationService).reconcileOnce();
        verify(reconciliationService).status();
    }

    @Test
    void followerRejectsManualReconciliationWithoutRunningIt() {
        FundSafetyService fundSafetyService = mock(FundSafetyService.class);
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        TradingLeadershipService leadershipService = mock(TradingLeadershipService.class);
        TradingController controller = controller(
                fundSafetyService,
                reconciliationService,
                leadershipService
        );

        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                controller::reconcileNow
        );

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        verifyNoInteractions(reconciliationService);
    }

    private static TradingController controller(
            FundSafetyService fundSafetyService,
            OrderReconciliationService reconciliationService
    ) {
        return controller(
                fundSafetyService,
                reconciliationService,
                mock(TradingLeadershipService.class)
        );
    }

    private static TradingController controller(
            FundSafetyService fundSafetyService,
            OrderReconciliationService reconciliationService,
            TradingLeadershipService leadershipService
    ) {
        return new TradingController(
                mock(TradingStrategyRegistry.class),
                mock(TradingStrategyEngine.class),
                mock(TradingStrategySelectionService.class),
                mock(BacktestService.class),
                mock(OrderLifecycleService.class),
                fundSafetyService,
                reconciliationService,
                leadershipService
        );
    }
}
