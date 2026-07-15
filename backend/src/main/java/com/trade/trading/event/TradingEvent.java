package com.trade.trading.event;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.TickerResp;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable envelope for data entering the trading module.
 *
 * <p>The envelope separates transport metadata from a type-safe payload. Event
 * IDs support log correlation; a shared correlation ID groups the snapshot and
 * candle batches collected for one strategy decision.</p>
 */
public record TradingEvent(
        String eventId,
        TradingEventType type,
        TradingEventSource source,
        String instrumentId,
        Instant occurredAt,
        Instant receivedAt,
        String correlationId,
        TradingEventPayload payload
) {
    public TradingEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId must not be blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        correlationId = correlationId == null || correlationId.isBlank() ? eventId : correlationId;
        Objects.requireNonNull(payload, "payload must not be null");
        if (type == TradingEventType.MARKET_SNAPSHOT && !(payload instanceof MarketSnapshotPayload)
                || type == TradingEventType.CANDLE_BATCH && !(payload instanceof CandleBatchPayload)) {
            throw new IllegalArgumentException("event type does not match payload: " + type);
        }
    }

    public static TradingEvent marketSnapshot(
            String instrumentId,
            TradingEventSource source,
            String correlationId,
            TickerResp ticker,
            OrderBookResp orderBook
    ) {
        Instant receivedAt = Instant.now();
        String eventId = UUID.randomUUID().toString();
        return new TradingEvent(
                eventId,
                TradingEventType.MARKET_SNAPSHOT,
                source,
                instrumentId,
                snapshotOccurredAt(ticker, orderBook, receivedAt),
                receivedAt,
                correlationId,
                new MarketSnapshotPayload(ticker, orderBook)
        );
    }

    public static TradingEvent candleBatch(
            String instrumentId,
            TradingEventSource source,
            String correlationId,
            String bar,
            List<CandleResp> candles
    ) {
        Instant receivedAt = Instant.now();
        String eventId = UUID.randomUUID().toString();
        return new TradingEvent(
                eventId,
                TradingEventType.CANDLE_BATCH,
                source,
                instrumentId,
                candleOccurredAt(candles, receivedAt),
                receivedAt,
                correlationId,
                new CandleBatchPayload(bar, candles)
        );
    }

    private static Instant snapshotOccurredAt(TickerResp ticker, OrderBookResp orderBook, Instant fallback) {
        Instant tickerAt = epochMillis(ticker == null ? null : ticker.getTs());
        Instant orderBookAt = epochMillis(orderBook == null ? null : orderBook.getTs());
        if (tickerAt == null) {
            return orderBookAt == null ? fallback : orderBookAt;
        }
        return orderBookAt == null || tickerAt.isAfter(orderBookAt) ? tickerAt : orderBookAt;
    }

    private static Instant candleOccurredAt(List<CandleResp> candles, Instant fallback) {
        if (candles == null) {
            return fallback;
        }
        Instant latest = null;
        for (CandleResp candle : candles) {
            Instant candidate = epochMillis(candle == null ? null : candle.getTs());
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest == null ? fallback : latest;
    }

    private static Instant epochMillis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
