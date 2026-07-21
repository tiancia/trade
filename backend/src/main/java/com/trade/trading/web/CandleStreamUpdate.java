package com.trade.trading.web;

import com.trade.client.okx.dto.CandleResp;

import java.time.Instant;
import java.util.List;

/** Incremental K-line update sent over server-sent events. */
public record CandleStreamUpdate(
        String eventId,
        String instrumentId,
        String bar,
        Instant receivedAt,
        List<CandleResp> candles
) {
}
