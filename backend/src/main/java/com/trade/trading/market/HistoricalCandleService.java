package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.trading.event.TradingEvent;
import com.trade.trading.event.TradingEventPublisher;
import com.trade.trading.event.TradingEventSource;
import com.trade.trading.persistence.OkxCandleCacheMapper;
import com.trade.trading.persistence.OkxCandleCacheRow;
import com.trade.common.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Component
public class HistoricalCandleService {
    private static final Logger log = LoggerFactory.getLogger(HistoricalCandleService.class);
    private static final int PAGE_SIZE = 300;
    private static final int DEFAULT_MAX_CANDLES = 10_000;
    private static final long REQUEST_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(110L);

    private final OkxApi okxApi;
    private final OkxCandleCacheMapper candleCacheMapper;
    private final TradingEventPublisher eventPublisher;

    public HistoricalCandleService(
            OkxApi okxApi,
            OkxCandleCacheMapper candleCacheMapper,
            TradingEventPublisher eventPublisher
    ) {
        this.okxApi = okxApi;
        this.candleCacheMapper = candleCacheMapper;
        this.eventPublisher = eventPublisher;
    }

    public List<CandleResp> historyCandles(String instId, String bar, Instant from, Instant to) {
        return historyCandles(instId, bar, from, to, DEFAULT_MAX_CANDLES);
    }

    public List<CandleResp> historyCandles(
            String instId,
            String bar,
            Instant from,
            Instant to,
            int maxCandles
    ) {
        long fromTs = from.toEpochMilli();
        long toTs = to.toEpochMilli();
        int safeMaxCandles = Math.max(maxCandles, 2);
        Map<Long, CandleResp> fetched = fetchPages(instId, bar, fromTs, toTs, safeMaxCandles);

        // Persistence is intentionally asynchronous. Merge the fresh response
        // with the existing cache so callers retain read-after-fetch behavior.
        Map<Long, CandleResp> merged = new LinkedHashMap<>();
        candleCacheMapper.findRange(instId, bar, fromTs, toTs).stream()
                .map(HistoricalCandleService::toCandle)
                .forEach(candle -> merged.put(ts(candle), candle));
        merged.putAll(fetched);
        List<CandleResp> result = merged.values().stream()
                .filter(candle -> inRange(ts(candle), fromTs, toTs))
                .sorted(Comparator.comparingLong(HistoricalCandleService::ts))
                .toList();
        if (result.size() > safeMaxCandles) {
            throw new IllegalArgumentException(
                    "History range contains more than maxCandles=" + safeMaxCandles
            );
        }
        return result;
    }

    private Map<Long, CandleResp> fetchPages(
            String instId,
            String bar,
            long fromTs,
            long toTs,
            int maxCandles
    ) {
        Map<Long, CandleResp> fetched = new LinkedHashMap<>();
        long cursor = toTs;
        long lastRequestAt = 0L;

        try {
            while (fetched.size() <= maxCandles) {
                lastRequestAt = throttle(lastRequestAt);
                List<CandleResp> page = OkxResponses.data(
                        okxApi.getHistoryCandles(new CandlesReq()
                                .setInstId(instId)
                                .setBar(bar)
                                .setBefore(String.valueOf(fromTs))
                                .setAfter(String.valueOf(cursor))
                                .setLimit(String.valueOf(PAGE_SIZE))),
                        "history candles"
                );
                if (page.isEmpty()) {
                    break;
                }

                long oldest = Long.MAX_VALUE;
                for (CandleResp candle : page) {
                    long timestamp = ts(candle);
                    if (inRange(timestamp, fromTs, toTs)) {
                        fetched.put(timestamp, candle);
                    }
                    if (timestamp > 0 && timestamp < oldest) {
                        oldest = timestamp;
                    }
                }
                publishPage(instId, bar, page);
                if (page.size() < PAGE_SIZE || oldest == Long.MAX_VALUE || oldest <= fromTs || oldest >= cursor) {
                    break;
                }
                cursor = oldest;
            }
        } catch (Exception e) {
            log.warn("Fetch OKX history candles failed, falling back to cache: {}", e.getMessage());
        }
        return fetched;
    }

    private void publishPage(String instId, String bar, List<CandleResp> page) {
        try {
            eventPublisher.publish(TradingEvent.candleBatch(
                    instId,
                    TradingEventSource.OKX_REST_HISTORY,
                    null,
                    bar,
                    page
            ));
        } catch (RuntimeException e) {
            // The fresh response remains valid input for this backtest even if
            // asynchronous cache publication fails.
            log.warn("Publish OKX history candles failed: {}", e.getMessage());
        }
    }

    private static long throttle(long lastRequestAt) {
        if (lastRequestAt > 0) {
            long remaining = REQUEST_INTERVAL_NANOS - (System.nanoTime() - lastRequestAt);
            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
            }
        }
        return System.nanoTime();
    }

    private static boolean inRange(long timestamp, long fromTs, long toTs) {
        return timestamp >= fromTs && timestamp <= toTs;
    }

    private static CandleResp toCandle(OkxCandleCacheRow row) {
        CandleResp candle = new CandleResp();
        candle.setTs(String.valueOf(row.getTs()));
        candle.setOpen(plain(row.getOpen()));
        candle.setHigh(plain(row.getHigh()));
        candle.setLow(plain(row.getLow()));
        candle.setClose(plain(row.getClose()));
        candle.setVol(plain(row.getVol()));
        candle.setVolCcy(plain(row.getVolCcy()));
        candle.setVolCcyQuote(plain(row.getVolCcyQuote()));
        candle.setConfirm(row.getConfirm());
        return candle;
    }

    private static long ts(CandleResp candle) {
        if (candle == null || candle.getTs() == null || candle.getTs().isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(candle.getTs());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String plain(BigDecimal value) {
        return TradingMath.plain(value);
    }
}
