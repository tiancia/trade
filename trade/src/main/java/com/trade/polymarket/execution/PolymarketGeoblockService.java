package com.trade.polymarket.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketApi;
import com.trade.polymarket.config.AiPolymarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PolymarketGeoblockService {
    private static final Logger log = LoggerFactory.getLogger(PolymarketGeoblockService.class);

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
            log.info("Polymarket geoblock check is disabled");
            return;
        }

        String raw;
        try {
            log.info("Polymarket geoblock check started: url={}", execution.getGeoblockUrl());
            raw = polymarketApi.getRawUrl(execution.getGeoblockUrl());
            JsonNode root = objectMapper.readTree(raw);
            boolean blocked = root.path("blocked").asBoolean(false)
                    || root.path("geoblocked").asBoolean(false)
                    || root.path("restricted").asBoolean(false);
            if (blocked) {
                log.warn("Polymarket geoblock check rejected live execution: response={}", raw);
                throw new IllegalStateException("Polymarket geoblock check rejected live execution, response=" + raw);
            }
            log.info("Polymarket geoblock check passed");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Polymarket geoblock check failed: {}", e.getMessage());
            throw new IllegalStateException(
                    "Polymarket geoblock check failed; live execution is blocked unless disabled explicitly",
                    e
            );
        }
    }
}
