package com.trade.trading.event;

import com.trade.client.okx.dto.CandleResp;

import java.util.List;

/** One immutable batch of candles for the same instrument and interval. */
public record CandleBatchPayload(String bar, List<CandleResp> candles) implements TradingEventPayload {
    public CandleBatchPayload {
        if (bar == null || bar.isBlank()) {
            throw new IllegalArgumentException("bar must not be blank");
        }
        candles = candles == null ? List.of() : List.copyOf(candles);
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("candles must not be empty");
        }
    }
}
