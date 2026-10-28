package com.trade.trading.infrastructure.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.trading.port.TradingEventPublisher;
import com.trade.trading.infrastructure.persistence.OkxCandleCacheMapper;
import com.trade.trading.infrastructure.persistence.OkxCandleCacheRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalCandleServiceTest {

    @Test
    void paginatesBackwardsAndReturnsChronologicalDeduplicatedCandles() {
        OkxApi api = mock(OkxApi.class);
        OkxCandleCacheMapper mapper = mock(OkxCandleCacheMapper.class);
        TradingEventPublisher publisher = mock(TradingEventPublisher.class);
        List<CandleResp> firstPage = descendingCandles(1_000L, 701L);
        List<CandleResp> secondPage = descendingCandles(700L, 699L);
        when(api.getHistoryCandles(any(CandlesReq.class))).thenReturn(
                OkxResponse.success(firstPage),
                OkxResponse.success(secondPage)
        );
        when(mapper.findRange("BTC-USDT", "1m", 1L, 1_001L)).thenReturn(List.of());
        HistoricalCandleService service = new HistoricalCandleService(api, mapper, publisher);

        List<CandleResp> result = service.historyCandles(
                "BTC-USDT",
                "1m",
                Instant.ofEpochMilli(1L),
                Instant.ofEpochMilli(1_001L),
                1_000
        );

        assertEquals(302, result.size());
        assertEquals("699", result.getFirst().getTs());
        assertEquals("1000", result.getLast().getTs());
        ArgumentCaptor<CandlesReq> requests = ArgumentCaptor.forClass(CandlesReq.class);
        verify(api, times(2)).getHistoryCandles(requests.capture());
        assertEquals("1001", requests.getAllValues().getFirst().getAfter());
        assertEquals("701", requests.getAllValues().get(1).getAfter());
        assertEquals("0", requests.getAllValues().getFirst().getBefore());
        assertEquals("300", requests.getAllValues().getFirst().getLimit());
        verify(publisher, times(2)).publish(any());
    }

    @Test
    void retainsTheInclusiveFirstCandleWithAnEmptyCacheAndExclusiveExchangePagination() {
        OkxApi api = mock(OkxApi.class);
        OkxCandleCacheMapper mapper = mock(OkxCandleCacheMapper.class);
        long from = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
        long interval = 900_000L;
        long to = from + 3 * interval;
        List<CandleResp> exchange = List.of(
                descendingCandles(from, from).getFirst(),
                descendingCandles(from + interval, from + interval).getFirst(),
                descendingCandles(from + 2 * interval, from + 2 * interval).getFirst());
        when(api.getHistoryCandles(any(CandlesReq.class))).thenAnswer(invocation -> {
            CandlesReq request = invocation.getArgument(0);
            long before = Long.parseLong(request.getBefore());
            long after = Long.parseLong(request.getAfter());
            return OkxResponse.success(exchange.stream()
                    .filter(candle -> Long.parseLong(candle.getTs()) > before && Long.parseLong(candle.getTs()) < after)
                    .sorted(java.util.Comparator.comparingLong((CandleResp candle) -> Long.parseLong(candle.getTs())).reversed())
                    .toList());
        });
        HistoricalCandleService service = new HistoricalCandleService(api, mapper, mock(TradingEventPublisher.class));
        List<CandleResp> result = service.historyCandles("BTC-USDT", "15m", Instant.ofEpochMilli(from), Instant.ofEpochMilli(to), 3);
        assertEquals(3, result.size());
        assertEquals(String.valueOf(from), result.getFirst().getTs());
        assertEquals(String.valueOf(from + 2 * interval), result.getLast().getTs());
        ArgumentCaptor<CandlesReq> captured = ArgumentCaptor.forClass(CandlesReq.class);
        verify(api).getHistoryCandles(captured.capture());
        assertEquals(String.valueOf(from - 1), captured.getValue().getBefore());
        assertEquals(String.valueOf(to), captured.getValue().getAfter());
    }

    @Test
    void excludesTheCachedUpperBoundaryBeforeEnforcingTheRequestedLimit() {
        OkxApi api = mock(OkxApi.class);
        OkxCandleCacheMapper mapper = mock(OkxCandleCacheMapper.class);
        long from = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
        long interval = 900_000L;
        long to = from + 3 * interval;
        when(api.getHistoryCandles(any())).thenReturn(OkxResponse.success(List.of()));
        when(mapper.findRange("BTC-USDT", "15m", from, to)).thenReturn(java.util.stream.LongStream.rangeClosed(0, 3)
                .mapToObj(i -> new OkxCandleCacheRow().setInstId("BTC-USDT").setBar("15m").setTs(from + i * interval)
                        .setOpen(java.math.BigDecimal.valueOf(100)).setHigh(java.math.BigDecimal.valueOf(101))
                        .setLow(java.math.BigDecimal.valueOf(99)).setClose(java.math.BigDecimal.valueOf(100)).setConfirm("1"))
                .toList());
        var service = new HistoricalCandleService(api, mapper, mock(TradingEventPublisher.class));
        var result = service.historyCandles("BTC-USDT", "15m", Instant.ofEpochMilli(from), Instant.ofEpochMilli(to), 3);
        assertEquals(3, result.size());
        assertEquals(String.valueOf(from), result.getFirst().getTs());
        assertEquals(String.valueOf(to - interval), result.getLast().getTs());
    }

    @Test
    void keepsFreshResponseWhenAsynchronousCachePublicationFails() {
        OkxApi api = mock(OkxApi.class);
        OkxCandleCacheMapper mapper = mock(OkxCandleCacheMapper.class);
        TradingEventPublisher publisher = mock(TradingEventPublisher.class);
        when(api.getHistoryCandles(any(CandlesReq.class)))
                .thenReturn(OkxResponse.success(descendingCandles(10L, 10L)));
        when(mapper.findRange("BTC-USDT", "1m", 1L, 20L)).thenReturn(List.of());
        doThrow(new IllegalStateException("queue unavailable")).when(publisher).publish(any());
        HistoricalCandleService service = new HistoricalCandleService(api, mapper, publisher);

        List<CandleResp> result = service.historyCandles(
                "BTC-USDT",
                "1m",
                Instant.ofEpochMilli(1L),
                Instant.ofEpochMilli(20L),
                100
        );

        assertEquals(1, result.size());
        assertEquals("10", result.getFirst().getTs());
    }

    private static List<CandleResp> descendingCandles(long newest, long oldest) {
        List<CandleResp> candles = new ArrayList<>();
        for (long timestamp = newest; timestamp >= oldest; timestamp--) {
            CandleResp candle = new CandleResp();
            candle.setTs(String.valueOf(timestamp));
            candle.setOpen("100");
            candle.setHigh("101");
            candle.setLow("99");
            candle.setClose("100");
            candle.setVolCcyQuote("1000");
            candle.setConfirm("1");
            candles.add(candle);
        }
        return candles;
    }
}
