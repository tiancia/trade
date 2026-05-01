package com.trade.trading.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.CandleChannelReq;
import com.trade.client.okx.ws.OkxWsEvent;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;
import com.trade.client.okx.ws.TickerChannelReq;
import com.trade.trading.config.AiTradingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxMarketDataWebSocketFeedTest {

    @Test
    void startSubscribesTickerAndOneMinuteCandles() {
        FakeOkxApi okxApi = new FakeOkxApi();
        AiTradingProperties properties = new AiTradingProperties();
        properties.setInstId("ETH-USDT");

        OkxMarketDataWebSocketFeed feed = new OkxMarketDataWebSocketFeed(okxApi, properties);

        feed.start();

        assertEquals("ETH-USDT", okxApi.tickerReq.getInstId());
        assertEquals("tickers", okxApi.tickerReq.getChannel());
        assertEquals("ETH-USDT", okxApi.candleReq.getInstId());
        assertEquals("candle1m", okxApi.candleReq.getChannel());
    }

    @Test
    void cachesLatestTickerAndRecentCandles() {
        AiTradingProperties properties = new AiTradingProperties();
        properties.getWebsocket().setCandleCacheLimit(2);
        OkxMarketDataWebSocketFeed feed = new OkxMarketDataWebSocketFeed(new FakeOkxApi(), properties);

        TickerResp ticker = new TickerResp();
        ticker.setInstId("BTC-USDT");
        ticker.setLast("50000");

        feed.handleTickerData(event(List.of(ticker)));
        feed.handleCandleData(event(List.of(
                candle("1000", "49000"),
                candle("2000", "50000"),
                candle("1500", "49500")
        )));

        assertTrue(feed.latestTicker().isPresent());
        assertEquals("50000", feed.latestTicker().orElseThrow().getLast());

        List<CandleResp> candles = feed.recentOneMinuteCandles(10);
        assertEquals(2, candles.size());
        assertEquals("2000", candles.get(0).getTs());
        assertEquals("1500", candles.get(1).getTs());
    }

    private static CandleResp candle(String ts, String close) {
        CandleResp candle = new CandleResp();
        candle.setTs(ts);
        candle.setClose(close);
        candle.setConfirm("1");
        return candle;
    }

    private static <T> OkxWsEvent<T> event(List<T> data) {
        OkxWsEvent<T> event = new OkxWsEvent<>();
        event.setData(data);
        return event;
    }

    private static class FakeOkxApi extends OkxApi {
        private TickerChannelReq tickerReq;
        private CandleChannelReq candleReq;

        FakeOkxApi() {
            super(new NoopOkxRestClient());
        }

        @Override
        public OkxWsSubscription subscribeTicker(TickerChannelReq req, OkxWsListener<TickerResp> listener) {
            this.tickerReq = req;
            return new NoopSubscription();
        }

        @Override
        public OkxWsSubscription subscribeCandles(CandleChannelReq req, OkxWsListener<CandleResp> listener) {
            this.candleReq = req;
            return new NoopSubscription();
        }
    }

    private static class NoopSubscription implements OkxWsSubscription {
        @Override
        public void unsubscribe() {
        }

        @Override
        public void close() {
        }
    }

    private static class NoopOkxRestClient implements OkxRestClient {
        @Override
        public <T> OkxResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }

        @Override
        public <T> OkxResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass) {
            return OkxResponse.success(List.of());
        }
    }
}
