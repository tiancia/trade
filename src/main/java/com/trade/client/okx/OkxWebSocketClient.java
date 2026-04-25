package com.trade.client.okx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.constdef.RequestPath;
import com.trade.dto.ws.OkxWsArg;
import com.trade.dto.ws.OkxWsEvent;
import com.trade.dto.ws.OkxWsListener;
import com.trade.dto.ws.OkxWsSubscription;
import com.trade.utils.Encryption;

import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OkxWebSocketClient {

    private static final String PRIVATE_VERIFY_PATH = "/users/self/verify";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Builds the WebSocket transport and JSON mapper used by OKX WS subscriptions.
     */
    public OkxWebSocketClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 7890)))
                .build();
        this.objectMapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Opens a public WebSocket connection and subscribes to one public channel.
     */
    public <T> OkxWsSubscription subscribePublic(
            OkxWsArg arg,
            Class<T> dataClass,
            OkxWsListener<T> listener
    ) {
        return subscribe(RequestPath.WS_PUBLIC_URL, arg, dataClass, listener, false);
    }

    /**
     * Opens a private WebSocket connection, logs in, then subscribes to one private channel.
     */
    public <T> OkxWsSubscription subscribePrivate(
            OkxWsArg arg,
            Class<T> dataClass,
            OkxWsListener<T> listener
    ) {
        return subscribe(RequestPath.WS_PRIVATE_URL, arg, dataClass, listener, true);
    }

    /**
     * Creates one WS session for one channel subscription.
     */
    private <T> OkxWsSubscription subscribe(
            String url,
            OkxWsArg arg,
            Class<T> dataClass,
            OkxWsListener<T> listener,
            boolean needLogin
    ) {
        WsSession<T> wsListener = new WsSession<>(arg, dataClass, listener, needLogin);
        CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .buildAsync(URI.create(url), wsListener);
        wsListener.setWebSocketFuture(future);
        return wsListener;
    }

    private final class WsSession<T> implements WebSocket.Listener, OkxWsSubscription {
        private final OkxWsArg arg;
        private final Class<T> dataClass;
        private final OkxWsListener<T> listener;
        private final boolean needLogin;
        private final StringBuilder messageBuffer = new StringBuilder();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
        private ScheduledFuture<?> heartbeatFuture;
        private CompletableFuture<WebSocket> webSocketFuture;

        private WsSession(
                OkxWsArg arg,
                Class<T> dataClass,
                OkxWsListener<T> listener,
                boolean needLogin
        ) {
            this.arg = arg;
            this.dataClass = dataClass;
            this.listener = listener;
            this.needLogin = needLogin;
        }

        /**
         * Stores the asynchronous connection future so close/unsubscribe can wait for it if needed.
         */
        private void setWebSocketFuture(CompletableFuture<WebSocket> webSocketFuture) {
            this.webSocketFuture = webSocketFuture;
            webSocketFuture.whenComplete((webSocket, error) -> {
                if (error != null) {
                    listener.onError(error);
                }
            });
        }

        /**
         * Starts heartbeat and either logs in first or directly subscribes after the socket opens.
         */
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocketRef.set(webSocket);
            WebSocket.Listener.super.onOpen(webSocket);
            startHeartbeat(webSocket);
            if (needLogin) {
                sendLogin(webSocket);
            } else {
                sendSubscribe(webSocket);
            }
        }

        /**
         * Buffers fragmented text frames and handles a complete OKX message.
         */
        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (!last) {
                return CompletableFuture.completedFuture(null);
            }

            String message = messageBuffer.toString();
            messageBuffer.setLength(0);
            handleMessage(webSocket, message);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Stops local resources and reports close events to the caller.
         */
        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            stopHeartbeat();
            scheduler.shutdownNow();
            listener.onClose(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Reports WebSocket transport errors to the caller.
         */
        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error);
        }

        /**
         * Sends an OKX unsubscribe request for this channel while leaving the socket open.
         */
        @Override
        public void unsubscribe() {
            WebSocket webSocket = awaitWebSocket();
            if (webSocket != null) {
                send(webSocket, "unsubscribe", arg);
            }
        }

        /**
         * Closes the WebSocket session and stops heartbeat resources.
         */
        @Override
        public void close() {
            stopHeartbeat();
            WebSocket webSocket = awaitWebSocket();
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "close");
            }
            scheduler.shutdownNow();
        }

        /**
         * Returns the connected socket, waiting briefly if the async connect is still in progress.
         */
        private WebSocket awaitWebSocket() {
            WebSocket current = webSocketRef.get();
            if (current != null) {
                return current;
            }
            if (webSocketFuture == null) {
                return null;
            }
            try {
                return webSocketFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                listener.onError(e);
                return null;
            }
        }

        /**
         * Routes login/subscribe events to onEvent and market/account payloads to onData.
         */
        private void handleMessage(WebSocket webSocket, String message) {
            try {
                if ("pong".equals(message)) {
                    return;
                }

                OkxWsEvent<T> event = parseEvent(message);
                if ("login".equals(event.getEvent()) && "0".equals(event.getCode())) {
                    listener.onEvent(event);
                    sendSubscribe(webSocket);
                    return;
                }

                if (event.getEvent() != null) {
                    listener.onEvent(event);
                    return;
                }

                listener.onData(event);
            } catch (Exception e) {
                listener.onError(e);
            }
        }

        /**
         * Parses OKX's WebSocket event wrapper with a typed data list.
         */
        private OkxWsEvent<T> parseEvent(String message) throws Exception {
            JavaType eventType = objectMapper.getTypeFactory()
                    .constructParametricType(OkxWsEvent.class, dataClass);
            return objectMapper.readValue(message, eventType);
        }

        /**
         * Sends the OKX private WebSocket login message using the WS-specific signature format.
         */
        private void sendLogin(WebSocket webSocket) {
            try {
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                String sign = Encryption.hmacSha256Base64(
                        timestamp + "GET" + PRIVATE_VERIFY_PATH,
                        RequestPath.getOkSecretKey()
                );
                Map<String, Object> loginArg = Map.of(
                        "apiKey", RequestPath.getOkAccessKey(),
                        "passphrase", RequestPath.getOkAccessPassphrase(),
                        "timestamp", timestamp,
                        "sign", sign
                );
                String payload = objectMapper.writeValueAsString(Map.of(
                        "op", "login",
                        "args", List.of(loginArg)
                ));
                webSocket.sendText(payload, true);
            } catch (Exception e) {
                listener.onError(e);
            }
        }

        /**
         * Sends the OKX subscribe operation for this session's channel argument.
         */
        private void sendSubscribe(WebSocket webSocket) {
            send(webSocket, "subscribe", arg);
        }

        /**
         * Sends a subscribe or unsubscribe operation with one channel argument.
         */
        private void send(WebSocket webSocket, String op, OkxWsArg arg) {
            try {
                Map<String, Object> payload = Map.of(
                        "op", op,
                        "args", List.of(arg)
                );
                webSocket.sendText(objectMapper.writeValueAsString(payload), true);
            } catch (Exception e) {
                listener.onError(e);
            }
        }

        /**
         * Keeps the OKX WebSocket connection alive by sending text ping messages.
         */
        private void startHeartbeat(WebSocket webSocket) {
            heartbeatFuture = scheduler.scheduleAtFixedRate(
                    () -> webSocket.sendText("ping", true),
                    25,
                    25,
                    TimeUnit.SECONDS
            );
        }

        /**
         * Cancels the heartbeat task if it has been started.
         */
        private void stopHeartbeat() {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(true);
            }
        }
    }
}
