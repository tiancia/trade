package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.event.TradingEvent;
import com.trade.trading.event.TradingEventPublisher;
import com.trade.trading.event.TradingEventSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-through market-data view used by the trading cockpit. */
@Component
public class TradingMarketDataQueryService {
    private static final Logger log = LoggerFactory.getLogger(TradingMarketDataQueryService.class);
    private static final int MAX_LIMIT = 300;
    private static final Map<String, String> SUPPORTED_BARS = Map.of(
            "1m", "1m",
            "5m", "5m",
            "15m", "15m",
            "1h", "1H",
            "4h", "4H"
    );

    private final OkxApi okxApi;
    private final TradingProperties properties;
    private final OkxMarketDataWebSocketFeed webSocketFeed;
    private final HotMarketDataCache hotMarketDataCache;
    private final TradingEventPublisher eventPublisher;

    public TradingMarketDataQueryService(
            OkxApi okxApi,
            TradingProperties properties,
            OkxMarketDataWebSocketFeed webSocketFeed,
            HotMarketDataCache hotMarketDataCache,
            TradingEventPublisher eventPublisher
    ) {
        this.okxApi = okxApi;
        this.properties = properties;
        this.webSocketFeed = webSocketFeed;
        this.hotMarketDataCache = hotMarketDataCache;
        this.eventPublisher = eventPublisher;
    }

    public CandleSeries recentCandles(String requestedInstId, String requestedBar, int requestedLimit) {
        String instId = instrument(requestedInstId);
        String bar = bar(requestedBar);
        int limit = limit(requestedLimit);

        List<CandleResp> candles = "1m".equals(bar)
                ? webSocketFeed.recentOneMinuteCandles(limit)
                : List.of();
        String source = candles.isEmpty() ? null : "WEBSOCKET";

        if (candles.isEmpty()) {
            candles = hotMarketDataCache.recentCandles(instId, bar, limit);
            source = candles.isEmpty() ? null : "HOT_CACHE";
        }
        if (candles.isEmpty()) {
            candles = OkxResponses.data(
                    okxApi.getCandles(new CandlesReq()
                            .setInstId(instId)
                            .setBar(bar)
                            .setLimit(String.valueOf(limit))),
                    "recent candles"
            );
            source = "OKX_REST";
            publish(instId, bar, candles);
        }

        List<CandleResp> chronological = chronological(candles, limit);
        return new CandleSeries(
                instId,
                bar,
                source == null ? "UNAVAILABLE" : source,
                latestTimestamp(chronological),
                chronological
        );
    }

    private void publish(String instId, String bar, List<CandleResp> candles) {
        if (candles == null || candles.isEmpty()) {
            return;
        }
        try {
            eventPublisher.publish(TradingEvent.candleBatch(
                    instId,
                    TradingEventSource.OKX_REST_EVENT_FALLBACK,
                    null,
                    bar,
                    candles
            ));
        } catch (RuntimeException e) {
            log.warn("Publish cockpit candle snapshot failed: {}", e.getMessage());
        }
    }

    private String instrument(String requested) {
        String configured = properties.getInstId();
        String candidate = requested == null || requested.isBlank() ? configured : requested.trim();
        if (!configured.equalsIgnoreCase(candidate)) {
            throw new IllegalArgumentException("Unsupported instrument: " + candidate);
        }
        return configured;
    }

    private static String bar(String requested) {
        String normalized = requested == null || requested.isBlank()
                ? "1m"
                : requested.trim().toLowerCase(Locale.ROOT);
        String supported = SUPPORTED_BARS.get(normalized);
        if (supported == null) {
            throw new IllegalArgumentException("Unsupported candle bar: " + requested);
        }
        return supported;
    }

    private static int limit(int requested) {
        if (requested < 2 || requested > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 2 and " + MAX_LIMIT);
        }
        return requested;
    }

    private static List<CandleResp> chronological(List<CandleResp> source, int limit) {
        Map<Long, CandleResp> deduplicated = new LinkedHashMap<>();
        if (source != null) {
            source.stream()
                    .filter(candle -> timestamp(candle) > 0L)
                    .sorted(Comparator.comparingLong(TradingMarketDataQueryService::timestamp))
                    .forEach(candle -> deduplicated.put(timestamp(candle), candle));
        }
        List<CandleResp> result = List.copyOf(deduplicated.values());
        return result.size() <= limit ? result : result.subList(result.size() - limit, result.size());
    }

    private static long timestamp(CandleResp candle) {
        try {
            return candle == null || candle.getTs() == null ? 0L : Long.parseLong(candle.getTs());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static Instant latestTimestamp(List<CandleResp> candles) {
        if (candles == null || candles.isEmpty()) {
            return Instant.now();
        }
        long timestamp = timestamp(candles.getLast());
        return timestamp > 0L ? Instant.ofEpochMilli(timestamp) : Instant.now();
    }
}
