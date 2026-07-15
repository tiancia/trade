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

@Component
public class HistoricalCandleService {
    private static final Logger log = LoggerFactory.getLogger(HistoricalCandleService.class);

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
        long fromTs = from.toEpochMilli();
        long toTs = to.toEpochMilli();
        List<CandleResp> fetched = List.of();
        try {
            fetched = OkxResponses.data(
                    okxApi.getHistoryCandles(new CandlesReq()
                            .setInstId(instId)
                            .setBar(bar)
                            .setBefore(String.valueOf(fromTs))
                            .setAfter(String.valueOf(toTs))
                            .setLimit("100")),
                    "history candles"
            );
            if (!fetched.isEmpty()) {
                eventPublisher.publish(TradingEvent.candleBatch(
                        instId,
                        TradingEventSource.OKX_REST_HISTORY,
                        null,
                        bar,
                        fetched
                ));
            }
        } catch (Exception e) {
            log.warn("Fetch OKX history candles failed, falling back to cache: {}", e.getMessage());
        }

        // Persistence is intentionally asynchronous. Merge the fresh response
        // with the existing cache so callers retain read-after-fetch behavior.
        Map<Long, CandleResp> merged = new LinkedHashMap<>();
        candleCacheMapper.findRange(instId, bar, fromTs, toTs).stream()
                .map(HistoricalCandleService::toCandle)
                .forEach(candle -> merged.put(ts(candle), candle));
        fetched.forEach(candle -> {
            long timestamp = ts(candle);
            if (timestamp > 0) {
                merged.put(timestamp, candle);
            }
        });
        return merged.values().stream()
                .sorted(Comparator.comparingLong(HistoricalCandleService::ts))
                .toList();
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
