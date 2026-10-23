package com.trade.trading.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.TradingRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradingPropertiesTest {
    @Test
    void marketDataDefaultsEnableStreamingWhileLiveExecutionRemainsGated() {
        TradingProperties properties = new TradingProperties();
        assertTrue(properties.getWebsocket().isEnabled());
        assertEquals(30_000L, properties.getWebsocket().getStaleTimeoutMs());
        assertEquals(15_000L, properties.getHotMarketDataCache().getCandleFreshnessMs());
        assertEquals(10_800_000L, properties.getHotMarketDataCache().getCandleTtlMs());
        assertFalse(properties.isLiveExecutionAllowed());
        assertEquals(200, properties.getFifteenMinuteCandleLimit());
    }

    @Test
    void bindsFifteenMinuteWarmupLimitAndRejectsUnsupportedOkxPageSizes() {
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("trade.trading.fifteen-minute-candle-limit", "150")
        ));
        TradingProperties properties = binder.bind("trade.trading", TradingProperties.class).get();
        assertEquals(150, properties.getFifteenMinuteCandleLimit());
        assertThrows(IllegalArgumentException.class, () -> properties.setFifteenMinuteCandleLimit(1));
        assertThrows(IllegalArgumentException.class, () -> properties.setFifteenMinuteCandleLimit(301));
        assertEquals(150, properties.getFifteenMinuteCandleLimit());
        assertFalse(properties.isLiveExecutionAllowed());
    }

    @Test
    void executionModeKeepsConfigurationAndRuntimeJsonContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String value : new String[]{"paper", "live", "backtest"}) {
            Binder binder = new Binder(new MapConfigurationPropertySource(
                    Map.of("trade.trading.execution-mode", value)
            ));
            TradingProperties properties = binder.bind("trade.trading", TradingProperties.class).get();
            ExecutionMode expected = ExecutionMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));

            assertEquals(expected, properties.getExecutionMode());
            assertFalse(properties.isLiveExecutionAllowed());
            TradingRuntimeStatus status = new TradingRuntimeStatus().setExecutionMode(expected);
            assertEquals(expected.name(), mapper.readTree(mapper.writeValueAsString(status))
                    .path("executionMode").asText());
        }
    }
}
