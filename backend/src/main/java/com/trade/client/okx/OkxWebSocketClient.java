package com.trade.client.okx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.ws.OkxWsArg;
import com.trade.client.okx.ws.OkxWsEvent;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

public class OkxWebSocketClient {
    private static final String PRIVATE_VERIFY_PATH = "/users/self/verify";
    private static final long IDLE_PING_NANOS = Duration.ofSeconds(25).toNanos();
    private static final long PING_TIMEOUT_NANOS = Duration.ofSeconds(10).toNanos();
    private static final long MAX_RECONNECT_DELAY_MS = 30_000L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OkxClientProperties properties;
    private final OkxSigner signer;
    private final Supplier<ScheduledExecutorService> schedulerFactory;
    private final LongSupplier nanoTime;
    private final LongUnaryOperator reconnectJitter;

    public OkxWebSocketClient() {
        this(new OkxClientProperties());
    }

    public OkxWebSocketClient(OkxClientProperties properties) {
        this(properties, buildHttpClient(properties));
    }

    OkxWebSocketClient(OkxClientProperties properties, HttpClient httpClient) {
        this(properties, httpClient, () -> Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "okx-websocket-recovery");
            thread.setDaemon(true);
            return thread;
        }), System::nanoTime, delay -> Math.min(MAX_RECONNECT_DELAY_MS,
                delay + ThreadLocalRandom.current().nextLong(delay / 5 + 1)));
    }

    OkxWebSocketClient(OkxClientProperties properties, HttpClient httpClient,
                       Supplier<ScheduledExecutorService> schedulerFactory,
                       LongSupplier nanoTime, LongUnaryOperator reconnectJitter) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.schedulerFactory = schedulerFactory;
        this.nanoTime = nanoTime;
        this.reconnectJitter = reconnectJitter;
        this.objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.signer = new OkxSigner(properties);
    }

    public <T> OkxWsSubscription subscribePublic(OkxWsArg arg, Class<T> dataClass, OkxWsListener<T> listener) {
        return subscribe(properties.getWsPublicUrl(), List.of(arg), dataClass, listener, false);
    }

    public <T> OkxWsSubscription subscribePrivate(OkxWsArg arg, Class<T> dataClass, OkxWsListener<T> listener) {
        return subscribe(properties.getWsPrivateUrl(), List.of(arg), dataClass, listener, true);
    }

    /** Public business channels such as candle1m use a separate OKX endpoint. */
    public <T> OkxWsSubscription subscribeBusiness(OkxWsArg arg, Class<T> dataClass, OkxWsListener<T> listener) {
        return subscribeBusiness(List.of(arg), dataClass, listener);
    }

    /** Shares one business connection across candle periods and resubscribes them together. */
    public <T> OkxWsSubscription subscribeBusiness(List<OkxWsArg> args, Class<T> dataClass,
                                                  OkxWsListener<T> listener) {
        return subscribe(properties.getWsBusinessUrl(), args, dataClass, listener, false);
    }

    private <T> OkxWsSubscription subscribe(String url, List<OkxWsArg> args, Class<T> dataClass,
                                           OkxWsListener<T> listener, boolean needLogin) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("At least one WebSocket channel is required");
        }
        WsSession<T> session = new WsSession<>(URI.create(url), List.copyOf(args), dataClass, listener, needLogin);
        session.connect();
        return session;
    }

    /** A subscription survives transport failures until its owner explicitly stops it. */
    private final class WsSession<T> implements OkxWsSubscription {
        private final URI uri;
        private final List<OkxWsArg> args;
        private final Class<T> dataClass;
        private final OkxWsListener<T> listener;
        private final boolean needLogin;
        private final ScheduledExecutorService scheduler = schedulerFactory.get();
        private Attempt current;
        private ScheduledFuture<?> reconnectFuture;
        private int reconnectAttempts;
        private boolean stopped;

        private WsSession(URI uri, List<OkxWsArg> args, Class<T> dataClass,
                          OkxWsListener<T> listener, boolean needLogin) {
            this.uri = uri;
            this.args = args;
            this.dataClass = dataClass;
            this.listener = Objects.requireNonNull(listener);
            this.needLogin = needLogin;
        }

        private void connect() {
            Attempt attempt;
            synchronized (this) {
                if (stopped) {
                    return;
                }
                reconnectFuture = null;
                attempt = new Attempt();
                current = attempt;
            }
            try {
                CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(30)).buildAsync(uri, attempt);
                synchronized (this) {
                    attempt.connectFuture = future;
                    if (stopped || current != attempt) {
                        future.cancel(true);
                    }
                }
                future.whenComplete((socket, error) -> {
                    if (error != null) {
                        recover(attempt, error);
                    } else if (!isCurrent(attempt)) {
                        socket.abort();
                    }
                });
            } catch (RuntimeException error) {
                recover(attempt, error);
            }
        }

        private synchronized boolean isCurrent(Attempt attempt) {
            return !stopped && current == attempt;
        }

        private void opened(Attempt attempt, WebSocket socket) {
            synchronized (this) {
                if (!isCurrent(attempt)) {
                    socket.abort();
                    return;
                }
                attempt.socket = socket;
                attempt.lastReceivedAt = nanoTime.getAsLong();
                attempt.sendTail = CompletableFuture.completedFuture(socket);
                attempt.heartbeatFuture = scheduler.scheduleAtFixedRate(
                        () -> heartbeat(attempt), 5, 5, TimeUnit.SECONDS);
            }
            try {
                socket.request(1);
                if (needLogin) {
                    sendLogin(attempt);
                } else {
                    sendOperation(attempt, "subscribe");
                }
            } catch (RuntimeException error) {
                recover(attempt, error);
            }
        }

        private void heartbeat(Attempt attempt) {
            boolean ping;
            synchronized (this) {
                if (!isCurrent(attempt)) {
                    return;
                }
                long now = nanoTime.getAsLong();
                if (attempt.pingSentAt >= 0) {
                    if (now - attempt.pingSentAt < PING_TIMEOUT_NANOS) {
                        return;
                    }
                    ping = false;
                } else {
                    if (now - attempt.lastReceivedAt < IDLE_PING_NANOS) {
                        return;
                    }
                    attempt.pingSentAt = now;
                    ping = true;
                }
            }
            if (ping) {
                sendText(attempt, "ping");
            } else {
                recover(attempt, new IOException("OKX WebSocket heartbeat timed out"));
            }
        }

        private void recover(Attempt attempt, Throwable error) {
            synchronized (this) {
                if (!isCurrent(attempt)) {
                    return;
                }
                current = null;
                attempt.cancelHeartbeat();
                if (attempt.connectFuture != null && !attempt.connectFuture.isDone()) {
                    attempt.connectFuture.cancel(true);
                }
                long baseDelay = Math.min(MAX_RECONNECT_DELAY_MS, 1_000L << Math.min(reconnectAttempts, 5));
                reconnectAttempts = Math.min(reconnectAttempts + 1, 6);
                long delay = Math.clamp(reconnectJitter.applyAsLong(baseDelay), 1_000L, MAX_RECONNECT_DELAY_MS);
                reconnectFuture = scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
            }
            if (attempt.socket != null) {
                attempt.socket.abort();
            }
            if (error != null) {
                reportError(error);
            }
        }

        private void reportError(Throwable error) {
            try {
                listener.onError(error);
            } catch (RuntimeException ignored) {
                // A consumer callback must not stop transport recovery.
            }
        }

        private void sendLogin(Attempt attempt) {
            try {
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                Map<String, Object> loginArg = Map.of(
                        "apiKey", properties.requiredAccessKey(),
                        "passphrase", properties.requiredPassphrase(),
                        "timestamp", timestamp,
                        "sign", signer.signWebSocket(timestamp, "GET", PRIVATE_VERIFY_PATH));
                sendText(attempt, objectMapper.writeValueAsString(Map.of("op", "login", "args", List.of(loginArg))));
            } catch (Exception error) {
                recover(attempt, error);
            }
        }

        private void sendOperation(Attempt attempt, String operation) {
            try {
                sendText(attempt, objectMapper.writeValueAsString(Map.of("op", operation, "args", args)));
            } catch (Exception error) {
                recover(attempt, error);
            }
        }

        private void sendText(Attempt attempt, String payload) {
            CompletableFuture<WebSocket> sent;
            synchronized (this) {
                if (!isCurrent(attempt)) {
                    return;
                }
                // JDK WebSocket permits only one outstanding text send per connection.
                sent = attempt.sendTail.thenCompose(socket -> socket.sendText(payload, true));
                attempt.sendTail = sent;
            }
            sent.whenComplete((socket, error) -> {
                if (error != null) {
                    recover(attempt, error);
                }
            });
        }

        private void handleMessage(Attempt attempt, String message) {
            try {
                if ("pong".equals(message)) {
                    return;
                }
                JavaType eventType = objectMapper.getTypeFactory().constructParametricType(OkxWsEvent.class, dataClass);
                OkxWsEvent<T> event = objectMapper.readValue(message, eventType);
                if (event.getEvent() != null) {
                    try {
                        listener.onEvent(event);
                    } catch (RuntimeException error) {
                        reportError(error);
                    }
                    if ("error".equals(event.getEvent())
                            || event.getCode() != null && !"0".equals(event.getCode())) {
                        recover(attempt, new IOException("OKX WebSocket operation rejected: code=" + event.getCode()));
                    } else if ("login".equals(event.getEvent())) {
                        sendOperation(attempt, "subscribe");
                    } else if ("subscribe".equals(event.getEvent())) {
                        synchronized (this) {
                            if (isCurrent(attempt)) {
                                reconnectAttempts = 0;
                            }
                        }
                    }
                } else {
                    synchronized (this) {
                        if (!isCurrent(attempt)) {
                            return;
                        }
                        reconnectAttempts = 0;
                    }
                    listener.onData(event);
                }
            } catch (Exception error) {
                reportError(error);
            }
        }

        /** Unsubscribe also stops this subscription's dedicated transport and retries. */
        @Override
        public void unsubscribe() {
            stop(true);
        }

        @Override
        public void close() {
            stop(false);
        }

        private void stop(boolean unsubscribe) {
            Attempt attempt;
            synchronized (this) {
                if (stopped) {
                    return;
                }
                stopped = true;
                attempt = current;
                current = null;
                if (reconnectFuture != null) {
                    reconnectFuture.cancel(false);
                }
                if (attempt != null) {
                    attempt.cancelHeartbeat();
                    if (attempt.connectFuture != null && !attempt.connectFuture.isDone()) {
                        attempt.connectFuture.cancel(true);
                    }
                }
                scheduler.shutdownNow();
            }
            if (attempt != null && attempt.socket != null) {
                WebSocket socket = attempt.socket;
                try {
                    if (unsubscribe && attempt.sendTail.isDone() && !attempt.sendTail.isCompletedExceptionally()) {
                        socket.sendText(objectMapper.writeValueAsString(Map.of("op", "unsubscribe", "args", args)), true)
                                .whenComplete((ignored, error) -> socket.abort());
                    } else {
                        socket.sendClose(WebSocket.NORMAL_CLOSURE, "close")
                                .whenComplete((ignored, error) -> socket.abort());
                    }
                } catch (Exception ignored) {
                    socket.abort();
                }
                // Never leave a half-open socket waiting for an asynchronous send or handshake.
                socket.abort();
            }
        }

        /** A fresh listener/buffer for each attempt prevents late callbacks from reviving old sockets. */
        private final class Attempt implements WebSocket.Listener {
            private final StringBuilder messageBuffer = new StringBuilder();
            private WebSocket socket;
            private CompletableFuture<WebSocket> connectFuture;
            private CompletableFuture<WebSocket> sendTail;
            private ScheduledFuture<?> heartbeatFuture;
            private long lastReceivedAt;
            private long pingSentAt = -1;

            private void cancelHeartbeat() {
                if (heartbeatFuture != null) {
                    heartbeatFuture.cancel(false);
                }
            }

            private void received() {
                synchronized (WsSession.this) {
                    if (isCurrent(this)) {
                        lastReceivedAt = nanoTime.getAsLong();
                        pingSentAt = -1;
                    }
                }
            }

            @Override
            public void onOpen(WebSocket socket) {
                opened(this, socket);
            }

            @Override
            public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                if (!isCurrent(this)) {
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    received();
                    messageBuffer.append(data);
                    if (last) {
                        String message = messageBuffer.toString();
                        messageBuffer.setLength(0);
                        handleMessage(this, message);
                    }
                    return CompletableFuture.completedFuture(null);
                } finally {
                    if (isCurrent(this)) {
                        // Every fragment, subscription ACK and text pong consumes JDK receive demand.
                        socket.request(1);
                    }
                }
            }

            @Override
            public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
                received();
                return WebSocket.Listener.super.onPing(socket, message);
            }

            @Override
            public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
                received();
                return WebSocket.Listener.super.onPong(socket, message);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
                if (isCurrent(this)) {
                    recover(this, null);
                    try {
                        listener.onClose(statusCode, reason);
                    } catch (RuntimeException error) {
                        reportError(error);
                    }
                }
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket socket, Throwable error) {
                recover(this, error);
            }
        }
    }

    private static HttpClient buildHttpClient(OkxClientProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30));
        OkxClientProperties.ProxyProperties proxy = properties.getProxy();
        if (proxy != null && proxy.isEnabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }
}
