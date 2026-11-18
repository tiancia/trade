package com.trade.polymarket.infrastructure.broker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.polymarket.PolymarketApi;
import com.trade.polymarket.infrastructure.config.AiPolymarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PolymarketGeoblockService {
    private static final Logger log = LoggerFactory.getLogger(PolymarketGeoblockService.class);

    private final PolymarketApi polymarketApi;
    private final AiPolymarketProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

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

        JsonNode response;
        try {
            log.info("Polymarket geoblock check started: url={}", execution.getGeoblockUrl());
            response = objectMapper.readTree(polymarketApi.getRawUrl(execution.getGeoblockUrl()));
        } catch (Exception e) {
            throw new IllegalStateException("Polymarket geoblock check failed; live execution is blocked", e);
        }

        // A successful HTTP response alone does not establish regional eligibility.
        boolean hasDecision = false;
        for (String field : List.of("blocked", "geoblocked", "restricted")) {
            JsonNode flag = response == null ? null : response.get(field);
            if (flag == null) continue;
            if (!flag.isBoolean()) {
                throw new IllegalStateException("Polymarket geoblock check returned an invalid restriction flag");
            }
            if (flag.booleanValue()) {
                throw new IllegalStateException("Polymarket geoblock check rejected live execution");
            }
            hasDecision = true;
        }
        if (!hasDecision) {
            throw new IllegalStateException("Polymarket geoblock check returned no restriction flag");
        }
        log.info("Polymarket geoblock check passed");
    }
}
