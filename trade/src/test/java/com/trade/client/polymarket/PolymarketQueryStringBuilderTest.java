package com.trade.client.polymarket;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolymarketQueryStringBuilderTest {
    @Test
    void repeatsCollectionQueryParameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("slug", List.of("first-market", "second market"));
        params.put("closed", false);

        String query = PolymarketQueryStringBuilder.build(
                "/markets",
                params
        );

        assertEquals("/markets?slug=first-market&slug=second+market&closed=false", query);
    }
}
