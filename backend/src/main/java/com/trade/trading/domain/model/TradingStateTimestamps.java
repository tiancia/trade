package com.trade.trading.domain.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Lenient reads of legacy state timestamps; strict persistence reads remain separate. */
public final class TradingStateTimestamps {
    private TradingStateTimestamps() {}

    public static Instant parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
