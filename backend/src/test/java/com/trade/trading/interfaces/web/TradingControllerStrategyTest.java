package com.trade.trading.interfaces.web;

import com.trade.trading.application.backtest.BacktestService;
import com.trade.trading.application.order.OrderLifecycleService;
import com.trade.trading.application.order.OrderReconciliationService;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.application.strategy.TradingStrategyEngine;
import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.application.strategy.TradingStrategySelectionService;
import com.trade.trading.domain.model.ActiveStrategySelection;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.config.TradingWebConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.ConcurrentModificationException;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class TradingControllerStrategyTest {
    private static final String TOKEN = "test-operator-token";
    private static final String ENDPOINT = "/api/trading/strategies/active";
    private static final String BODY = "{\"strategyId\":\"defensive\",\"expectedRevision\":4}";
    private final TradingProperties properties = new TradingProperties();
    private final TradingStrategySelectionService selection = mock(TradingStrategySelectionService.class);

    @Test
    void blankOperatorConfigurationDisablesStrategyMutation() throws Exception {
        mvc().perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(selection);
    }

    @Test
    void missingAndWrongTokensCannotChangeStrategy() throws Exception {
        properties.getFundSafety().setOperatorToken(TOKEN);
        MockMvc mvc = mvc();
        mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put(ENDPOINT).header("X-Trading-Operator-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(selection);
    }

    @Test
    void authorizedSelectionPreservesRevisionAndErrorResponses() throws Exception {
        properties.getFundSafety().setOperatorToken(TOKEN);
        when(selection.activate("defensive", 4L))
                .thenReturn(new ActiveStrategySelection("defensive", 5L, null))
                .thenThrow(new ConcurrentModificationException("stale revision"))
                .thenThrow(new IllegalArgumentException("unknown strategy"));
        MockMvc mvc = mvc();
        mvc.perform(put(ENDPOINT).header("X-Trading-Operator-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value("defensive"))
                .andExpect(jsonPath("$.revision").value(5));
        mvc.perform(put(ENDPOINT).header("X-Trading-Operator-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        mvc.perform(put(ENDPOINT).header("X-Trading-Operator-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verify(selection, times(3)).activate("defensive", 4L);
    }

    @Test
    void allowedBrowserOriginCanPreflightOperatorHeaderButOtherOriginsCannot() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(options(ENDPOINT).header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "Content-Type,X-Trading-Operator-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Headers", "Content-Type, X-Trading-Operator-Token"));
        mvc.perform(options(ENDPOINT).header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "X-Trading-Operator-Token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(selection);
    }

    private MockMvc mvc() {
        TradingController controller = new TradingController(
                mock(TradingStrategyRegistry.class), mock(TradingStrategyEngine.class), selection,
                mock(BacktestService.class), mock(OrderLifecycleService.class), mock(FundSafetyService.class),
                properties, mock(OrderReconciliationService.class), mock(TradingLeadershipService.class));
        ExposedCorsRegistry cors = new ExposedCorsRegistry();
        new TradingWebConfiguration(properties).addCorsMappings(cors);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.setCorsConfigurations(cors.configurations());
        return MockMvcBuilders.standaloneSetup(controller).addFilters(new CorsFilter(source)).build();
    }

    private static class ExposedCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
