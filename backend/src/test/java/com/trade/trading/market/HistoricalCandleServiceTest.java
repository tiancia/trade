package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.trading.event.TradingEventPublisher;
import com.trade.trading.persistence.OkxCandleCacheMapper;
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
        assertEquals("1", requests.getAllValues().getFirst().getBefore());
        assertEquals("300", requests.getAllValues().getFirst().getLimit());
        verify(publisher, times(2)).publish(any());
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
