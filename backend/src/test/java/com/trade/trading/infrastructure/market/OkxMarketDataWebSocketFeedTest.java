package com.trade.trading.infrastructure.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxRestClient;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.CandleChannelReq;
import com.trade.client.okx.ws.OkxWsArg;
import com.trade.client.okx.ws.OkxWsEvent;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;
import com.trade.client.okx.ws.TickerChannelReq;
import com.trade.trading.application.event.CandleBatchPayload;
import com.trade.trading.application.event.MarketSnapshotPayload;
import com.trade.trading.application.event.TradingEvent;
import com.trade.trading.application.event.TradingEventPublishResult;
import com.trade.trading.application.event.TradingEventSource;
import com.trade.trading.application.market.CandleFreshness;
import com.trade.trading.port.TradingEventPublisher;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxMarketDataWebSocketFeedTest {
    private static final List<String> BARS = List.of("1m", "5m", "15m", "1H", "4H");

    @Test
    void startUsesTickerAndOneBatchForAllCandlePeriodsAndStopClosesBoth() {
        Fixture fixture = new Fixture();
        fixture.properties.setInstId("ETH-USDT");
        fixture.feed.start();
        fixture.feed.start();

        assertEquals("ETH-USDT", fixture.api.tickerReq.getInstId());
        assertEquals("tickers", fixture.api.tickerReq.getChannel());
        assertEquals(BARS.stream().map(bar -> "candle" + bar).toList(),
                fixture.api.candleRequests.stream().map(CandleChannelReq::getChannel).toList());
        assertTrue(fixture.api.candleRequests.stream().allMatch(req -> "ETH-USDT".equals(req.getInstId())));
        assertEquals(2, fixture.api.openedSubscriptions.get());

        fixture.feed.stop();
        fixture.feed.stop();
        assertEquals(2, fixture.api.closedSubscriptions.get());
    }

    @Test
    void cachesEachPeriodSeparatelyAndPublishesOnlyUnifiedEvents() {
        Fixture fixture = new Fixture();
        fixture.properties.getWebsocket().setCandleCacheLimit(2);
        fixture.feed.start();
        fixture.feed.handleTickerData(event(List.of(ticker(fixture.clock.instant(), "50000"))));

        for (int index = 0; index < BARS.size(); index++) {
            String bar = BARS.get(index);
            long interval = CandleFreshness.intervalMillis(bar);
            long current = currentTimestamp(fixture.clock.instant(), bar);
            fixture.api.candleListener.onData(candleEvent(bar, List.of(
                    candle(current - interval * 2, "49000"),
                    candle(current, String.valueOf(50000 + index)),
                    candle(current - interval, "49500")
            )));
            List<CandleResp> candles = fixture.feed.recentCandles(bar.toLowerCase(), 10);
            assertEquals(2, candles.size());
            assertEquals(String.valueOf(current), candles.getFirst().getTs());
            assertEquals(String.valueOf(50000 + index), candles.getFirst().getClose());
            assertEquals(String.valueOf(current - interval), candles.getLast().getTs());
        }

        assertEquals("50000", fixture.feed.latestTicker().orElseThrow().getLast());
        assertEquals(fixture.feed.recentCandles("1m", 10), fixture.feed.recentOneMinuteCandles(10));
        assertEquals(6, fixture.publisher.events.size());
        TradingEvent snapshot = fixture.publisher.events.getFirst();
        assertTrue(snapshot.payload() instanceof MarketSnapshotPayload);
        assertEquals("50000", ((MarketSnapshotPayload) snapshot.payload()).ticker().getLast());
        for (int index = 0; index < BARS.size(); index++) {
            TradingEvent update = fixture.publisher.events.get(index + 1);
            assertEquals(TradingEventSource.OKX_WEBSOCKET, update.source());
            assertEquals("BTC-USDT", update.instrumentId());
            CandleBatchPayload payload = (CandleBatchPayload) update.payload();
            assertEquals(BARS.get(index), payload.bar());
            assertEquals(3, payload.candles().size());
        }
        fixture.feed.stop();
    }

    @Test
    void eachPeriodExpiresIndependentlyAfterNoUpdates() {
        Fixture fixture = new Fixture();
        fixture.feed.start();
        for (String bar : BARS) {
            fixture.api.candleListener.onData(candleEvent(bar,
                    List.of(candle(currentTimestamp(fixture.clock.instant(), bar), "50000"))));
        }
        fixture.clock.advance(Duration.ofSeconds(20));
        fixture.api.candleListener.onData(candleEvent("5m",
                List.of(candle(currentTimestamp(fixture.clock.instant(), "5m"), "50100"))));
        fixture.clock.advance(Duration.ofSeconds(11));

        assertTrue(fixture.feed.recentOneMinuteCandles(10).isEmpty());
        assertEquals("50100", fixture.feed.recentCandles("5m", 10).getFirst().getClose());
        assertTrue(fixture.feed.recentCandles("15m", 10).isEmpty());
        assertTrue(fixture.feed.recentCandles("1H", 10).isEmpty());
        assertTrue(fixture.feed.recentCandles("4H", 10).isEmpty());
        fixture.feed.stop();
    }

    @Test
    void previousPeriodCandlesCannotRemainCurrentOrRenewReceptionAge() {
        Fixture fixture = new Fixture();
        fixture.properties.getWebsocket().setStaleTimeoutMs(120_000L);
        fixture.feed.start();
        long minute = currentTimestamp(fixture.clock.instant(), "1m");
        fixture.feed.handleCandleData(event(List.of(candle(minute, "50000"))));
        assertEquals(1, fixture.feed.recentOneMinuteCandles(10).size());

        fixture.clock.advance(Duration.ofSeconds(41));
        fixture.feed.handleCandleData(event(List.of(candle(minute, "50100"))));
        assertTrue(fixture.feed.recentOneMinuteCandles(10).isEmpty());
        fixture.feed.handleCandleData(event(List.of(
                candle(currentTimestamp(fixture.clock.instant(), "1m"), "50200"))));
        assertEquals("50200", fixture.feed.recentOneMinuteCandles(10).getFirst().getClose());
        fixture.feed.stop();
    }

    @Test
    void ignoresInvalidChannelsInstrumentsAndCandlePayloads() {
        Fixture fixture = new Fixture();
        fixture.feed.start();
        long current = currentTimestamp(fixture.clock.instant(), "1m");
        OkxWsEvent<CandleResp> wrongInstrument = candleEvent("1m", List.of(candle(current, "50000")));
        wrongInstrument.getArg().setInstId("ETH-USDT");
        fixture.api.candleListener.onData(wrongInstrument);
        fixture.api.candleListener.onData(candleEvent("2m", List.of(candle(current, "50000"))));
        fixture.api.candleListener.onData(event(List.of(candle(current, "50000"))));
        fixture.api.candleListener.onData(null);
        fixture.api.candleListener.onData(candleEvent("1m", List.of()));
        CandleResp malformed = candle(current, "50000");
        malformed.setTs("invalid");
        fixture.api.candleListener.onData(candleEvent("1m", Arrays.asList(
                null, malformed, candle(-1, "50000"), candle(current + 60_000L, "50000"),
                candle(current, "NaN"), candle(current, "0"))));

        assertTrue(fixture.feed.recentOneMinuteCandles(10).isEmpty());
        assertTrue(fixture.publisher.events.isEmpty());
        fixture.api.candleListener.onData(candleEvent("1m", List.of(candle(current, "50000"))));
        fixture.clock.advance(Duration.ofSeconds(31));
        fixture.api.candleListener.onData(candleEvent("1m", List.of(malformed)));
        assertTrue(fixture.feed.recentOneMinuteCandles(10).isEmpty());
        assertEquals(1, fixture.publisher.events.size());
        fixture.feed.stop();
    }

    @Test
    void tickerRejectsInvalidOldFutureAndOtherInstrumentObservations() {
        Fixture fixture = new Fixture();
        fixture.feed.start();
        Instant now = fixture.clock.instant();
        fixture.feed.handleTickerData(event(List.of(ticker(now, "50000"))));
        assertEquals("50000", fixture.feed.latestTicker().orElseThrow().getLast());
        TickerResp wrongInstrument = ticker(now, "50100");
        wrongInstrument.setInstId("ETH-USDT");
        fixture.feed.handleTickerData(event(Arrays.asList(
                null, wrongInstrument, ticker(now.minusSeconds(31), "50200"),
                ticker(now.plusSeconds(1), "50300"), ticker(now, "NaN"))));
        fixture.feed.handleTickerData(null);
        fixture.feed.handleTickerData(event(List.of()));
        assertEquals(1, fixture.publisher.events.size());

        fixture.clock.advance(Duration.ofSeconds(31));
        fixture.feed.handleTickerData(event(List.of(ticker(now, "50400"))));
        assertTrue(fixture.feed.latestTicker().isEmpty());
        assertEquals(1, fixture.publisher.events.size());
        fixture.feed.stop();
    }

    @Test
    void stopClearsEveryPeriodAndRejectsLateCallbacksUntilRestart() {
        Fixture fixture = new Fixture();
        fixture.feed.start();
        fixture.feed.handleTickerData(event(List.of(ticker(fixture.clock.instant(), "50000"))));
        for (String bar : BARS) {
            fixture.api.candleListener.onData(candleEvent(bar,
                    List.of(candle(currentTimestamp(fixture.clock.instant(), bar), "50000"))));
        }
        fixture.feed.stop();
        int eventsBeforeLateCallbacks = fixture.publisher.events.size();
        fixture.feed.handleTickerData(event(List.of(ticker(fixture.clock.instant(), "50100"))));
        fixture.api.candleListener.onData(candleEvent("1m", List.of(
                candle(currentTimestamp(fixture.clock.instant(), "1m"), "50100"))));
        assertEquals(eventsBeforeLateCallbacks, fixture.publisher.events.size());
        assertTrue(fixture.feed.latestTicker().isEmpty());
        for (String bar : BARS) {
            assertTrue(fixture.feed.recentCandles(bar, 10).isEmpty());
        }
        fixture.feed.start();
        assertEquals(4, fixture.api.openedSubscriptions.get());
        assertTrue(fixture.feed.recentOneMinuteCandles(10).isEmpty());
        fixture.feed.stop();
    }

    @Test
    void disabledModuleOrWebsocketNeverSubscribes() {
        for (boolean disableModule : List.of(true, false)) {
            Fixture fixture = new Fixture();
            fixture.properties.setEnabled(!disableModule);
            fixture.properties.getWebsocket().setEnabled(disableModule);
            fixture.feed.start();
            assertEquals(0, fixture.api.openedSubscriptions.get());
            assertTrue(fixture.feed.latestTicker().isEmpty());
            assertTrue(fixture.feed.recentCandles("5m", 10).isEmpty());
            fixture.feed.stop();
        }
    }

    private static long currentTimestamp(Instant now, String bar) {
        long interval = CandleFreshness.intervalMillis(bar);
        return Math.floorDiv(now.toEpochMilli(), interval) * interval;
    }

    private static TickerResp ticker(Instant now, String last) {
        TickerResp ticker = new TickerResp();
        ticker.setInstId("BTC-USDT");
        ticker.setLast(last);
        ticker.setTs(String.valueOf(now.toEpochMilli()));
        return ticker;
    }

    private static CandleResp candle(long ts, String close) {
        CandleResp candle = new CandleResp();
        candle.setTs(String.valueOf(ts));
        candle.setClose(close);
        candle.setConfirm("1");
        return candle;
    }

    private static OkxWsEvent<CandleResp> candleEvent(String bar, List<CandleResp> candles) {
        OkxWsEvent<CandleResp> event = event(candles);
        event.setArg(new OkxWsArg().setChannel("candle" + bar).setInstId("BTC-USDT"));
        return event;
    }

    private static <T> OkxWsEvent<T> event(List<T> data) {
        OkxWsEvent<T> event = new OkxWsEvent<>();
        event.setData(data);
        return event;
    }

    private static class Fixture {
        private final FakeOkxApi api = new FakeOkxApi();
        private final TradingProperties properties = new TradingProperties();
        private final CapturingPublisher publisher = new CapturingPublisher();
        private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T04:00:20Z"));
        private final OkxMarketDataWebSocketFeed feed = new OkxMarketDataWebSocketFeed(api, properties, publisher, clock);

        private Fixture() {
            properties.getWebsocket().setStaleTimeoutMs(30_000L);
        }
    }

    private static class CapturingPublisher implements TradingEventPublisher {
        private final List<TradingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public TradingEventPublishResult publish(TradingEvent event) {
            events.add(event);
            return TradingEventPublishResult.ACCEPTED;
        }
    }

    private static class FakeOkxApi extends OkxApi {
        private TickerChannelReq tickerReq;
        private List<CandleChannelReq> candleRequests;
        private OkxWsListener<CandleResp> candleListener;
        private final AtomicInteger openedSubscriptions = new AtomicInteger();
        private final AtomicInteger closedSubscriptions = new AtomicInteger();

        FakeOkxApi() {
            super(new NoopOkxRestClient());
        }

        @Override
        public OkxWsSubscription subscribeTicker(TickerChannelReq req, OkxWsListener<TickerResp> listener) {
            this.tickerReq = req;
            openedSubscriptions.incrementAndGet();
            return new NoopSubscription(closedSubscriptions);
        }

        @Override
        public OkxWsSubscription subscribeCandles(List<CandleChannelReq> reqs, OkxWsListener<CandleResp> listener) {
            this.candleRequests = List.copyOf(reqs);
            this.candleListener = listener;
            openedSubscriptions.incrementAndGet();
            return new NoopSubscription(closedSubscriptions);
        }
    }

    private record NoopSubscription(AtomicInteger closedSubscriptions) implements OkxWsSubscription {
        @Override
        public void unsubscribe() {
        }

        @Override
        public void close() {
            closedSubscriptions.incrementAndGet();
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

    private static class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
