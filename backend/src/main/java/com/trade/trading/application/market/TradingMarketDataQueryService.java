package com.trade.trading.application.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.trading.application.event.TradingEvent;
import com.trade.trading.application.event.TradingEventSource;
import com.trade.trading.application.port.HotMarketDataCache;
import com.trade.trading.application.port.TradingEventPublisher;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.application.port.TradingMarketFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
    private final TradingMarketFeed webSocketFeed;
    private final HotMarketDataCache hotMarketDataCache;
    private final TradingEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public TradingMarketDataQueryService(
            OkxApi okxApi,
            TradingProperties properties,
            TradingMarketFeed webSocketFeed,
            HotMarketDataCache hotMarketDataCache,
            TradingEventPublisher eventPublisher
    ) {
        this(okxApi, properties, webSocketFeed, hotMarketDataCache, eventPublisher, Clock.systemUTC());
    }

    TradingMarketDataQueryService(OkxApi okxApi, TradingProperties properties,
                                 TradingMarketFeed webSocketFeed, HotMarketDataCache hotMarketDataCache,
                                 TradingEventPublisher eventPublisher, Clock clock) {
        this.okxApi = okxApi;
        this.properties = properties;
        this.webSocketFeed = webSocketFeed;
        this.hotMarketDataCache = hotMarketDataCache;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public CandleSeries recentCandles(String requestedInstId, String requestedBar, int requestedLimit) {
        String instId = instrument(requestedInstId);
        String bar = bar(requestedBar);
        int limit = limit(requestedLimit);

        List<CandleResp> webSocketCandles = current(webSocketFeed.recentCandles(bar, limit), bar, limit);
        List<CandleResp> candles = webSocketCandles;
        String source = candles.isEmpty() ? "UNAVAILABLE" : "WEBSOCKET";

        if (!hasContinuousHistory(candles, bar, limit)) {
            List<CandleResp> cached = current(hotMarketDataCache.recentCandles(instId, bar, limit), bar, limit);
            candles = merge(limit, cached, webSocketCandles);
            source = webSocketCandles.isEmpty() ? (cached.isEmpty() ? "UNAVAILABLE" : "HOT_CACHE") : "WEBSOCKET";
        }
        if (!hasContinuousHistory(candles, bar, limit)) {
            List<CandleResp> fetched = OkxResponses.data(
                    okxApi.getCandles(new CandlesReq()
                            .setInstId(instId)
                            .setBar(bar)
                            .setLimit(String.valueOf(limit))),
                    "recent candles"
            );
            // A candle may have changed on WS while the HTTP request was in flight.
            List<CandleResp> latestWebSocket = current(webSocketFeed.recentCandles(bar, limit), bar, limit);
            webSocketCandles = current(webSocketCandles, bar, limit);
            candles = merge(limit, candles, fetched, webSocketCandles, latestWebSocket);
            source = latestWebSocket.isEmpty() && webSocketCandles.isEmpty() ? "OKX_REST" : "WEBSOCKET";
            publish(instId, bar, candles);
        }

        List<CandleResp> chronological = chronological(candles, limit);
        return new CandleSeries(
                instId,
                bar,
                source,
                latestTimestamp(chronological),
                chronological,
                clock.instant()
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

    private List<CandleResp> current(List<CandleResp> candles, String bar, int limit) {
        List<CandleResp> valid = chronological(candles, limit);
        return CandleFreshness.isCurrent(valid, bar, clock.instant()) ? valid : List.of();
    }

    private static boolean hasContinuousHistory(List<CandleResp> candles, String bar, int limit) {
        if (candles.size() < limit) {
            return false;
        }
        long interval = CandleFreshness.intervalMillis(bar);
        for (int index = 1; index < candles.size(); index++) {
            if (timestamp(candles.get(index)) - timestamp(candles.get(index - 1)) != interval) {
                return false;
            }
        }
        return true;
    }

    @SafeVarargs
    private final List<CandleResp> merge(int limit, List<CandleResp>... sources) {
        List<CandleResp> combined = new ArrayList<>();
        for (List<CandleResp> source : sources) {
            if (source != null) {
                combined.addAll(source);
            }
        }
        return chronological(combined, limit);
    }

    private List<CandleResp> chronological(List<CandleResp> source, int limit) {
        Map<Long, CandleResp> deduplicated = new LinkedHashMap<>();
        if (source != null) {
            source.stream()
                    .filter(candle -> timestamp(candle) > 0L && timestamp(candle) <= clock.millis())
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

    private Instant latestTimestamp(List<CandleResp> candles) {
        if (candles == null || candles.isEmpty()) {
            return clock.instant();
        }
        long timestamp = timestamp(candles.getLast());
        return timestamp > 0L ? Instant.ofEpochMilli(timestamp) : clock.instant();
    }
}
