package com.trade.trading.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradingStateTimestampsTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "2026-10-29", " 2026-10-29T01:02:03Z "})
    void preservesMissingAndMalformedLegacyStateBehavior(String value) {
        assertNull(TradingStateTimestamps.parseOrNull(value));
    }

    @Test
    void preservesInstantPrecision() {
        Instant time = Instant.parse("2026-10-29T01:02:03.123456789Z");
        assertEquals(time, TradingStateTimestamps.parseOrNull(time.toString()));
    }
}
