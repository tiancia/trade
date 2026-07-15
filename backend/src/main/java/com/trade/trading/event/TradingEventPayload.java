package com.trade.trading.event;

/** Marker for payloads carried by the unified trading event envelope. */
public sealed interface TradingEventPayload permits MarketSnapshotPayload, CandleBatchPayload {
}
