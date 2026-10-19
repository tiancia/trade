package com.trade.trading.interfaces.web;

import com.trade.trading.application.strategy.TradingStrategyRegistry;
import com.trade.trading.domain.model.StrategyConfigurationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StrategyConfigurationControllerTest {
    private static final String ENDPOINT = "/api/trading/strategies/threshold/configuration";
    private final TradingStrategyRegistry registry = mock(TradingStrategyRegistry.class);

    @Test
    void retainsExpectedVersionAndMapsConflictsAndValidationErrors() throws Exception {
        var snapshot = new StrategyConfigurationVersion("threshold", "threshold-event", "1m", 3,
                "2026-10-19T00:00:00Z", "trigger adjustment", Map.of("price-move-trigger-percent", 0.02));
        when(registry.updateConfiguration("threshold", 2L, "trigger adjustment", snapshot.params()))
                .thenReturn(snapshot)
                .thenThrow(new ConcurrentModificationException("Version changed"))
                .thenThrow(new IllegalArgumentException("Invalid parameter"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StrategyConfigurationController(registry)).build();
        String body = """
                {"expectedVersion":2,"reason":"trigger adjustment","params":{"price-move-trigger-percent":0.02}}
                """;
        mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(put(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(registry, times(3)).updateConfiguration("threshold", 2L, "trigger adjustment", snapshot.params());
    }

    @Test
    void historyPassesBoundedPaginationToRegistry() throws Exception {
        when(registry.configurationHistory("threshold", 2, 10)).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StrategyConfigurationController(registry)).build();
        mvc.perform(get(ENDPOINT + "/history?offset=2&limit=10"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(registry).configurationHistory("threshold", 2, 10);
    }

    @Test
    void decimalParametersSurviveBrowserReadAndResaveWithoutRounding() throws Exception {
        var snapshot = new StrategyConfigurationVersion("threshold", "threshold-event", "1m", 1,
                "2026-10-19T00:00:00Z", "initial", Map.of(
                "price-move-trigger-percent", new BigDecimal("0.123456789012345678"),
                "price-move-window-candles", 5, "require-confirmed-candle", true));
        when(registry.configuration("threshold")).thenReturn(snapshot);
        when(registry.configurationHistory("threshold", 0, 100)).thenReturn(List.of(snapshot));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StrategyConfigurationController(registry)).build();
        mvc.perform(get(ENDPOINT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.params.price-move-trigger-percent").value("0.123456789012345678"))
                .andExpect(jsonPath("$.params.price-move-window-candles").value(5))
                .andExpect(jsonPath("$.params.require-confirmed-candle").value(true));
        mvc.perform(get(ENDPOINT + "/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].params.price-move-trigger-percent").value("0.123456789012345678"));
    }
}
