package com.trade.trading.interfaces.web;

import com.trade.trading.application.backtest.BacktestService;
import com.trade.trading.application.market.TradingMarketDataQueryService;
import com.trade.trading.application.order.OrderLifecycleService;
import com.trade.trading.application.order.OrderReconciliationService;
import com.trade.trading.application.order.UnknownOrderRecoveryService;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.application.strategy.TradingStrategyEngine;
import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.application.strategy.TradingStrategySelectionService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TradingExceptionHandlerTest {
    private final TradingStrategyRegistry strategies = mock(TradingStrategyRegistry.class);
    private final TradingStrategySelectionService selection = mock(TradingStrategySelectionService.class);
    private final FundSafetyService safety = mock(FundSafetyService.class);
    private final PaperAccountService paper = mock(PaperAccountService.class);
    private final UnknownOrderRecoveryService recovery = mock(UnknownOrderRecoveryService.class);
    private final TradingMarketDataQueryService market = mock(TradingMarketDataQueryService.class);
    private final BacktestService backtests = mock(BacktestService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new TradingController(strategies, mock(TradingStrategyEngine.class), selection, backtests,
                    mock(OrderLifecycleService.class), safety, mock(OrderReconciliationService.class),
                    mock(TradingLeadershipService.class)),
            new StrategyConfigurationController(strategies), new PaperAccountController(paper),
            new UnknownOrderRecoveryController(recovery),
            new TradingMarketDataController(market, mock(TradingCandleStream.class)))
            .setControllerAdvice(new TradingExceptionHandler()).build();

    @ParameterizedTest
    @CsvSource({
            "PUT,/api/trading/strategies/active",
            "PUT,/api/trading/strategies/threshold/configuration",
            "POST,/api/trading/safety/stop",
            "POST,/api/trading/safety/resume",
            "POST,/api/trading/paper/sessions",
            "POST,/api/trading/orders/order-key/abandon",
            "POST,/api/trading/backtests"
    })
    void springRejectsMissingNullAndMalformedBodiesBeforeCallingServices(String method, String path) throws Exception {
        for (String body : List.of("", "null", "{")) {
            mvc.perform(request(HttpMethod.valueOf(method), path).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(strategies, selection, safety, paper, recovery, backtests);
    }

    @Test
    void marketValidationRetainsReasonWhileUnexpectedStateErrorsPropagate() throws Exception {
        when(market.recentCandles("BTC-USDT", "1m", 80))
                .thenThrow(new IllegalArgumentException("invalid candle request"))
                .thenThrow(new IllegalStateException("upstream unavailable", new IllegalArgumentException("invalid upstream data")));
        mvc.perform(get("/api/trading/market/candles"))
                .andExpect(status().isBadRequest()).andExpect(status().reason("invalid candle request"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThrows(ServletException.class, () -> mvc.perform(get("/api/trading/market/candles")));
    }

    @Test
    void stateConflictsAndBacktestNotFoundKeepTheirSpecificStatus() throws Exception {
        when(safety.resume(1L, "checked", "confirm")).thenThrow(new IllegalStateException("still pending"));
        mvc.perform(post("/api/trading/safety/resume").contentType("application/json")
                        .content("""
                                {"expectedVersion":1,"reason":"checked","confirmation":"confirm"}
                                """))
                .andExpect(status().isConflict()).andExpect(status().reason("still pending"));
        when(paper.session("missing")).thenThrow(new IllegalStateException("session unavailable"));
        mvc.perform(get("/api/trading/paper/sessions/missing"))
                .andExpect(status().isConflict()).andExpect(status().reason("session unavailable"));
        when(backtests.get("missing")).thenThrow(new IllegalArgumentException("Backtest not found"));
        mvc.perform(get("/api/trading/backtests/missing"))
                .andExpect(status().isNotFound()).andExpect(status().reason("Backtest not found"));
    }
}
