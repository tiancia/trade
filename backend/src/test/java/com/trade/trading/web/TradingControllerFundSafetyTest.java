package com.trade.trading.web;

import com.trade.trading.application.OrderReconciliationService;
import com.trade.trading.application.TradingStrategyEngine;
import com.trade.trading.application.TradingStrategySelectionService;
import com.trade.trading.backtest.BacktestService;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.order.OrderLifecycleService;
import com.trade.trading.risk.FundSafetyService;
import com.trade.trading.risk.FundSafetyState;
import com.trade.trading.risk.FundSafetyStatus;
import com.trade.trading.strategy.TradingStrategyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TradingControllerFundSafetyTest {
    @Test
    void operatorTokenProtectsFundMutations() {
        TradingProperties properties = new TradingProperties();
        properties.getFundSafety().setOperatorToken("long-random-operator-secret");
        FundSafetyService fundSafetyService = mock(FundSafetyService.class);
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        TradingController controller = controller(properties, fundSafetyService, reconciliationService);

        ResponseStatusException forbidden = assertThrows(
                ResponseStatusException.class,
                () -> controller.stopFunds(
                        "wrong-secret",
                        new TradingController.FundStopRequest("incident")
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        verifyNoInteractions(fundSafetyService);

        FundSafetyState halted = new FundSafetyState()
                .setStatus(FundSafetyStatus.HALTED)
                .setReason("incident");
        when(fundSafetyService.halt("operator-api", "incident")).thenReturn(halted);

        assertSame(
                halted,
                controller.stopFunds(
                        "long-random-operator-secret",
                        new TradingController.FundStopRequest("incident")
                )
        );
        verify(fundSafetyService).halt("operator-api", "incident");
    }

    @Test
    void blankConfiguredTokenDisablesManualReconciliation() {
        TradingProperties properties = new TradingProperties();
        OrderReconciliationService reconciliationService = mock(OrderReconciliationService.class);
        TradingController controller = controller(
                properties,
                mock(FundSafetyService.class),
                reconciliationService
        );

        ResponseStatusException unavailable = assertThrows(
                ResponseStatusException.class,
                () -> controller.reconcileNow("any-token")
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        verifyNoInteractions(reconciliationService);
    }

    private static TradingController controller(
            TradingProperties properties,
            FundSafetyService fundSafetyService,
            OrderReconciliationService reconciliationService
    ) {
        return new TradingController(
                mock(TradingStrategyRegistry.class),
                mock(TradingStrategyEngine.class),
                mock(TradingStrategySelectionService.class),
                mock(BacktestService.class),
                mock(OrderLifecycleService.class),
                fundSafetyService,
                properties,
                reconciliationService
        );
    }
}
