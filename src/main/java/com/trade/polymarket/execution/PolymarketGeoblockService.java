package com.trade.polymarket.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketApi;
import com.trade.polymarket.config.AiPolymarketProperties;
import org.springframework.stereotype.Component;

@Component
public class PolymarketGeoblockService {
    private final PolymarketApi polymarketApi;
    private final AiPolymarketProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PolymarketGeoblockService(PolymarketApi polymarketApi, AiPolymarketProperties properties) {
        this.polymarketApi = polymarketApi;
        this.properties = properties;
    }

    public void assertAllowed() {
        AiPolymarketProperties.ExecutionProperties execution = properties.getExecution();
        if (!execution.isGeoblockCheckEnabled()) {
            return;
        }

        String raw;
        try {
            raw = polymarketApi.getRawUrl(execution.getGeoblockUrl());
            JsonNode root = objectMapper.readTree(raw);
            boolean blocked = root.path("blocked").asBoolean(false)
                    || root.path("geoblocked").asBoolean(false)
                    || root.path("restricted").asBoolean(false);
            if (blocked) {
                throw new IllegalStateException("Polymarket geoblock check rejected live execution, response=" + raw);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Polymarket geoblock check failed; live execution is blocked unless disabled explicitly",
                    e
            );
        }
    }
}
