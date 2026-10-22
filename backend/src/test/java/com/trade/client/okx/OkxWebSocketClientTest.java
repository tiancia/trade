package com.trade.client.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.OkxWsEvent;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;
import com.trade.client.okx.ws.TickerChannelReq;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OkxWebSocketClientTest {
    @Test
    void continuesReceivingAfterAckFragmentsPongAndMalformedMessage() {
        Harness harness = new Harness();
        try (var subscription = harness.subscribe()) {
            Connection connection = harness.connections.getFirst();
            connection.text("{\"event\":\"subscribe\",\"arg\":{\"channel\":\"tickers\",\"instId\":\"BTC-USDT\"}}", true);
            connection.text("{\"data\":[{\"last\":", false);
            connection.text("\"100\"}]}", true);
            connection.text("pong", true);
            connection.text("invalid", true);
            connection.text("{\"data\":[{\"last\":\"101\"}]}", true);

            assertEquals(List.of("100", "101"), harness.data.stream()
                    .map(event -> event.getData().getFirst().getLast()).toList());
            assertEquals(1, harness.errors.size());
            assertTrue(harness.reconnects.isEmpty());
            verify(connection.socket, times(7)).request(1);
        }
    }

    @Test
    void businessSubscriptionUsesOneConfiguredEndpointWithoutLoginForAllPeriods() throws Exception {
        Harness harness = new Harness();
        harness.properties.setWsBusinessUrl("wss://business.example/ws/v5/business");
        var args = List.of(
                new TickerChannelReq().setInstId("BTC-USDT").toArg().setChannel("candle1m"),
                new TickerChannelReq().setInstId("BTC-USDT").toArg().setChannel("candle5m"),
                new TickerChannelReq().setInstId("BTC-USDT").toArg().setChannel("candle15m"));
        try (var subscription = harness.client.subscribeBusiness(args, TickerResp.class, new OkxWsListener<>() {})) {
            assertEquals(1, harness.connections.size());
            verify(harness.builder).buildAsync(eq(URI.create(harness.properties.getWsBusinessUrl())), any(WebSocket.Listener.class));
            JsonNode payload = new ObjectMapper().readTree(harness.connections.getFirst().sent.getFirst());
            assertEquals("subscribe", payload.path("op").asText());
            assertEquals(List.of("candle1m", "candle5m", "candle15m"),
                    payload.path("args").findValuesAsText("channel"));
        }
    }

    @Test
    void reconnectsAndResubscribesAfterCloseAndIgnoresOldSocketCallbacks() {
        Harness harness = new Harness();
        try (var subscription = harness.subscribe()) {
            Connection first = harness.connections.getFirst();
            first.text("{\"data\":[{\"last\":", false);
            first.listener.onClose(first.socket, 1006, "lost");
            assertEquals(1_000L, harness.reconnects.getFirst().delayMs);
            harness.runReconnect();
            Connection second = harness.connections.getLast();

            assertEquals(2, harness.connections.size());
            assertTrue(second.sent.getFirst().contains("\"op\":\"subscribe\""));
            first.listener.onError(first.socket, new IOException("late failure"));
            first.text("\"1\"}]}", true);
            second.text("{\"data\":[{\"last\":\"102\"}]}", true);

            assertEquals(1, harness.data.size());
            assertEquals("102", harness.data.getFirst().getData().getFirst().getLast());
            assertTrue(harness.reconnects.isEmpty());
            verify(first.socket, atLeastOnce()).abort();
        }
    }

    @Test
    void failedConnectionsBackOffToThirtySecondsAndCloseCancelsRecovery() {
        Harness harness = new Harness();
        harness.autoOpen = false;
        OkxWsSubscription subscription = harness.subscribe();
        List<Long> delays = new ArrayList<>();
        for (int attempt = 0; attempt < 7; attempt++) {
            harness.connections.getLast().future.completeExceptionally(new IOException("offline"));
            delays.add(harness.reconnects.getFirst().delayMs);
            if (attempt < 6) {
                harness.runReconnect();
            }
        }
        assertEquals(List.of(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays);

        Task retry = harness.reconnects.getFirst();
        subscription.close();
        retry.run();
        assertEquals(7, harness.connections.size());
        assertTrue(retry.cancelled);
        verify(harness.scheduler).shutdownNow();
    }

    @Test
    void asynchronousSubscribeSendFailureTriggersRecovery() {
        Harness harness = new Harness();
        harness.nextSendFailure = new IOException("send failed");
        try (var subscription = harness.subscribe()) {
            assertEquals(1, harness.reconnects.size());
            assertEquals(1, harness.errors.size());
            harness.runReconnect();
            assertEquals(2, harness.connections.size());
            assertTrue(harness.connections.getLast().sent.getFirst().contains("\"op\":\"subscribe\""));
        }
    }

    @Test
    void sendsPingOnlyAfterIdleAndRecoversWhenNoReplyArrives() {
        Harness harness = new Harness();
        try (var subscription = harness.subscribe()) {
            Connection connection = harness.connections.getFirst();
            harness.advanceSeconds(24);
            harness.heartbeat();
            assertEquals(1, connection.sent.size());
            harness.advanceSeconds(1);
            harness.heartbeat();
            assertEquals("ping", connection.sent.getLast());
            harness.advanceSeconds(9);
            harness.heartbeat();
            assertTrue(harness.reconnects.isEmpty());
            harness.advanceSeconds(1);
            harness.heartbeat();
            assertEquals(1, harness.reconnects.size());
            verify(connection.socket, atLeastOnce()).abort();
        }
    }

    @Test
    void anyIncomingMessageAnswersHeartbeatAndActiveDataAvoidsUnneededPings() {
        Harness harness = new Harness();
        try (var subscription = harness.subscribe()) {
            Connection connection = harness.connections.getFirst();
            harness.advanceSeconds(25);
            harness.heartbeat();
            connection.text("pong", true);
            harness.advanceSeconds(24);
            harness.heartbeat();
            assertEquals(1, connection.sent.stream().filter("ping"::equals).count());
            connection.text("{\"data\":[{\"last\":\"100\"}]}", true);
            harness.advanceSeconds(10);
            harness.heartbeat();
            assertTrue(harness.reconnects.isEmpty());
            assertEquals(1, connection.sent.stream().filter("ping"::equals).count());
        }
    }

    @Test
    void closeWhileConnectingCancelsWithoutWaitingAndRejectsLateOpen() {
        Harness harness = new Harness();
        harness.autoOpen = false;
        var subscription = harness.subscribe();
        Connection connection = harness.connections.getFirst();

        subscription.close();
        assertTrue(connection.future.isCancelled());
        connection.listener.onOpen(connection.socket);

        verify(connection.socket).abort();
        assertTrue(connection.sent.isEmpty());
        assertTrue(harness.reconnects.isEmpty());
    }

    @Test
    void unsubscribeStopsRetryAndSubsequentCloseIsIdempotent() {
        Harness harness = new Harness();
        var subscription = harness.subscribe();
        Connection connection = harness.connections.getFirst();
        subscription.unsubscribe();
        connection.listener.onClose(connection.socket, 1000, "unsubscribed");
        connection.listener.onError(connection.socket, new IOException("late"));
        subscription.close();

        assertTrue(connection.sent.getLast().contains("\"op\":\"unsubscribe\""));
        assertTrue(harness.reconnects.isEmpty());
        verify(harness.scheduler, times(1)).shutdownNow();
    }

    @Test
    void privateReconnectLogsInAgainBeforeResubscribing() {
        Harness harness = new Harness();
        harness.properties.setAccessKey("test-access");
        harness.properties.setSecretKey("test-secret");
        harness.properties.setPassphrase("test-passphrase");
        try (var subscription = harness.client.subscribePrivate(
                new TickerChannelReq().setInstId("BTC-USDT").toArg(),
                TickerResp.class, new OkxWsListener<>() {})) {
            Connection first = harness.connections.getFirst();
            assertTrue(first.sent.getFirst().contains("\"op\":\"login\""));
            assertEquals(1, first.sent.size());
            first.text("{\"event\":\"login\",\"code\":\"0\"}", true);
            assertTrue(first.sent.getLast().contains("\"op\":\"subscribe\""));

            first.listener.onClose(first.socket, 1006, "lost");
            harness.runReconnect();
            Connection second = harness.connections.getLast();
            assertTrue(second.sent.getFirst().contains("\"op\":\"login\""));
            assertEquals(1, second.sent.size());
            second.text("{\"event\":\"login\",\"code\":\"0\"}", true);
            assertTrue(second.sent.getLast().contains("\"op\":\"subscribe\""));
        }
    }

    @Test
    void operationRejectionTriggersRecoveryAndThrowingConsumerDoesNotBlockIt() {
        Harness harness = new Harness();
        try (var subscription = harness.client.subscribePublic(
                new TickerChannelReq().setInstId("BTC-USDT").toArg(),
                TickerResp.class, new OkxWsListener<>() {
                    @Override
                    public void onEvent(OkxWsEvent<TickerResp> event) {
                        throw new IllegalStateException("consumer");
                    }
                    @Override
                    public void onError(Throwable error) {
                        throw new IllegalStateException("consumer error");
                    }
                })) {
            harness.connections.getFirst().text("{\"event\":\"error\",\"code\":\"60012\"}", true);
            assertEquals(1, harness.reconnects.size());
            harness.runReconnect();
            assertEquals(2, harness.connections.size());
        }
    }

    private static final class Harness {
        final HttpClient transport = mock(HttpClient.class);
        final WebSocket.Builder builder = mock(WebSocket.Builder.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final OkxClientProperties properties = new OkxClientProperties();
        final AtomicLong now = new AtomicLong(1_000_000L);
        final List<Connection> connections = new ArrayList<>();
        final ArrayDeque<Task> reconnects = new ArrayDeque<>();
        final List<Task> heartbeats = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final List<OkxWsEvent<TickerResp>> data = new ArrayList<>();
        final OkxWebSocketClient client;
        boolean autoOpen = true;
        Throwable nextSendFailure;

        Harness() {
            when(transport.newWebSocketBuilder()).thenReturn(builder);
            when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
            when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
                Task task = new Task(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                reconnects.add(task);
                return task.future;
            });
            when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(call -> {
                        Task task = new Task(call.getArgument(0), call.getArgument(1), call.getArgument(3));
                        heartbeats.add(task);
                        return task.future;
                    });
            when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(call -> {
                WebSocket socket = mock(WebSocket.class);
                Connection connection = new Connection(socket, call.getArgument(1));
                connections.add(connection);
                when(socket.sendText(any(CharSequence.class), eq(true))).thenAnswer(send -> {
                    connection.sent.add(send.getArgument(0).toString());
                    if (nextSendFailure != null) {
                        Throwable failure = nextSendFailure;
                        nextSendFailure = null;
                        return CompletableFuture.failedFuture(failure);
                    }
                    return CompletableFuture.completedFuture(socket);
                });
                when(socket.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(socket));
                if (autoOpen) {
                    connection.listener.onOpen(socket);
                    connection.future.complete(socket);
                }
                return connection.future;
            });
            client = new OkxWebSocketClient(properties, transport, () -> scheduler, now::get, delay -> delay);
        }

        OkxWsSubscription subscribe() {
            return client.subscribePublic(new TickerChannelReq().setInstId("BTC-USDT").toArg(),
                    TickerResp.class, new OkxWsListener<>() {
                        @Override
                        public void onData(OkxWsEvent<TickerResp> event) { data.add(event); }
                        @Override
                        public void onError(Throwable error) { errors.add(error); }
                    });
        }

        void advanceSeconds(long seconds) {
            now.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        }

        void heartbeat() {
            heartbeats.getLast().run();
        }

        void runReconnect() {
            reconnects.removeFirst().run();
        }
    }

    private static final class Connection {
        final WebSocket socket;
        final WebSocket.Listener listener;
        final CompletableFuture<WebSocket> future = new CompletableFuture<>();
        final List<String> sent = new ArrayList<>();

        Connection(WebSocket socket, WebSocket.Listener listener) {
            this.socket = socket;
            this.listener = listener;
        }

        void text(String message, boolean last) {
            listener.onText(socket, message, last);
        }
    }

    private static final class Task {
        final Runnable runnable;
        final long delayMs;
        final ScheduledFuture<?> future = mock(ScheduledFuture.class);
        boolean cancelled;

        Task(Runnable runnable, long delay, TimeUnit unit) {
            this.runnable = runnable;
            this.delayMs = unit.toMillis(delay);
            when(future.cancel(anyBoolean())).thenAnswer(call -> {
                cancelled = true;
                return true;
            });
        }

        void run() {
            if (!cancelled) {
                runnable.run();
            }
        }
    }
}
