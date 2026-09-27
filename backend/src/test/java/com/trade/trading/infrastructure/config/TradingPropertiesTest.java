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

class TradingPropertiesTest {
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
