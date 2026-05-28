package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.trading.persistence.OkxCandleCacheMapper;
import com.trade.trading.persistence.OkxCandleCacheRow;
import com.trade.trading.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
public class HistoricalCandleService {
    private static final Logger log = LoggerFactory.getLogger(HistoricalCandleService.class);

    private final OkxApi okxApi;
    private final OkxCandleCacheMapper candleCacheMapper;

    public HistoricalCandleService(OkxApi okxApi, OkxCandleCacheMapper candleCacheMapper) {
        this.okxApi = okxApi;
        this.candleCacheMapper = candleCacheMapper;
    }

    public List<CandleResp> historyCandles(String instId, String bar, Instant from, Instant to) {
        long fromTs = from.toEpochMilli();
        long toTs = to.toEpochMilli();
        try {
            List<CandleResp> fetched = OkxResponses.data(
                    okxApi.getHistoryCandles(new CandlesReq()
                            .setInstId(instId)
                            .setBar(bar)
                            .setBefore(String.valueOf(fromTs))
                            .setAfter(String.valueOf(toTs))
                            .setLimit("100")),
                    "history candles"
            );
            for (CandleResp candle : fetched) {
                upsert(instId, bar, candle);
            }
        } catch (Exception e) {
            log.warn("Fetch OKX history candles failed, falling back to cache: {}", e.getMessage());
        }
        return candleCacheMapper.findRange(instId, bar, fromTs, toTs).stream()
                .map(HistoricalCandleService::toCandle)
                .sorted(Comparator.comparingLong(HistoricalCandleService::ts))
                .toList();
    }

    private void upsert(String instId, String bar, CandleResp candle) {
        long ts = ts(candle);
        if (ts <= 0) {
            return;
        }
        candleCacheMapper.upsert(new OkxCandleCacheRow()
                .setInstId(instId)
                .setBar(bar)
                .setTs(ts)
                .setOpen(decimal(candle.getOpen()))
                .setHigh(decimal(candle.getHigh()))
                .setLow(decimal(candle.getLow()))
                .setClose(decimal(candle.getClose()))
                .setVol(decimal(candle.getVol()))
                .setVolCcy(decimal(candle.getVolCcy()))
                .setVolCcyQuote(decimal(candle.getVolCcyQuote()))
                .setConfirm(candle.getConfirm()));
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

    private static BigDecimal decimal(String value) {
        return TradingMath.decimal(value);
    }

    private static String plain(BigDecimal value) {
        return TradingMath.plain(value);
    }
}
