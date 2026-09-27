package com.trade.trading.infrastructure.event;

import com.trade.trading.application.event.CandleBatchPayload;
import com.trade.trading.application.event.MarketSnapshotPayload;
import com.trade.trading.application.event.TradingEvent;
import com.trade.trading.application.event.TradingEventHandlingResult;
import com.trade.trading.application.port.HotMarketDataCache;
import com.trade.trading.application.port.TradingEventHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Updates Redis hot data on the event consumer thread. */
@Component
@Order(10)
public class HotMarketDataCacheEventHandler implements TradingEventHandler {
    private final HotMarketDataCache hotMarketDataCache;

    public HotMarketDataCacheEventHandler(HotMarketDataCache hotMarketDataCache) {
        this.hotMarketDataCache = hotMarketDataCache;
    }

    @Override
    public String name() {
        return "hot-market-data-cache";
    }

    @Override
    public boolean supports(TradingEvent event) {
        return event != null && (event.payload() instanceof MarketSnapshotPayload
                || event.payload() instanceof CandleBatchPayload);
    }

    @Override
    public TradingEventHandlingResult handle(TradingEvent event) {
        HotMarketDataCache.CacheWriteResult result;
        if (event.payload() instanceof MarketSnapshotPayload snapshot) {
            result = hotMarketDataCache.putSnapshot(
                    event.instrumentId(),
                    snapshot.ticker(),
                    snapshot.orderBook()
            );
        } else if (event.payload() instanceof CandleBatchPayload candleBatch) {
            result = hotMarketDataCache.putCandles(
                    event.instrumentId(),
                    candleBatch.bar(),
                    candleBatch.candles()
            );
        } else {
            return TradingEventHandlingResult.SKIPPED;
        }
        return switch (result) {
            case CACHED -> TradingEventHandlingResult.PROCESSED;
            case SKIPPED -> TradingEventHandlingResult.SKIPPED;
            case FAILED -> TradingEventHandlingResult.FAILED;
        };
    }
}
