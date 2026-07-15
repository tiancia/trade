package com.trade.trading.event;

import com.trade.trading.persistence.OkxMarketDataStore;
import org.springframework.stereotype.Component;

/** Persists market events on the consumer thread, never on a producer thread. */
@Component
public class MarketDataPersistenceEventHandler implements TradingEventHandler {
    private final OkxMarketDataStore marketDataStore;

    public MarketDataPersistenceEventHandler(OkxMarketDataStore marketDataStore) {
        this.marketDataStore = marketDataStore;
    }

    @Override
    public String name() {
        return "market-data-persistence";
    }

    @Override
    public boolean supports(TradingEvent event) {
        return event != null && (event.payload() instanceof MarketSnapshotPayload
                || event.payload() instanceof CandleBatchPayload);
    }

    @Override
    public TradingEventHandlingResult handle(TradingEvent event) {
        OkxMarketDataStore.SaveResult result;
        if (event.payload() instanceof MarketSnapshotPayload snapshot) {
            result = marketDataStore.saveSnapshotWithResult(
                    event.instrumentId(),
                    event.source().persistenceValue(),
                    snapshot.ticker(),
                    snapshot.orderBook()
            );
        } else if (event.payload() instanceof CandleBatchPayload candleBatch) {
            result = marketDataStore.saveCandlesWithResult(
                    event.instrumentId(),
                    candleBatch.bar(),
                    candleBatch.candles()
            );
        } else {
            return TradingEventHandlingResult.SKIPPED;
        }
        return switch (result) {
            case SAVED -> TradingEventHandlingResult.PROCESSED;
            case SKIPPED -> TradingEventHandlingResult.SKIPPED;
            case FAILED -> TradingEventHandlingResult.FAILED;
        };
    }
}
