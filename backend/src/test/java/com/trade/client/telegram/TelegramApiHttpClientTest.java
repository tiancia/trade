package com.trade.client.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.telegram.dto.TelegramInlineKeyboardButton;
import com.trade.client.telegram.dto.TelegramInlineKeyboardMarkup;
import com.trade.client.telegram.dto.TelegramMessage;
import com.trade.client.telegram.dto.TelegramUpdate;
import com.trade.client.telegram.dto.TelegramUser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramApiHttpClientTest {
    private static final String TEST_TOKEN = "123456:test-secret-token";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_RESPONSE = """
            {"ok":true,"result":{"id":4503599627370495,"is_bot":true,
            "first_name":"Trade","last_name":"Bot","username":"trade_test_bot",
            "can_join_groups":true,"future_field":"ignored"},"future_envelope":true}
            """;

    @Test
    void getMeUsesJsonPostAndMapsLargeIdsWithUnknownFields() throws Exception {
        CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);
        TelegramUser user = api(sender).getMe();

        assertEquals(4503599627370495L, user.id());
        assertTrue(user.isBot());
        assertEquals("Trade", user.firstName());
        assertEquals("Bot", user.lastName());
        assertEquals("trade_test_bot", user.username());
        HttpRequest request = sender.requests.getFirst();
        assertEquals("POST", request.method());
        assertEquals(URI.create("https://api.telegram.org/bot" + TEST_TOKEN + "/getMe"), request.uri());
        assertTrue(request.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertTrue(requestBody(request).isObject());
        assertEquals(0, requestBody(request).size());
        assertEquals(1, sender.requests.size());
    }

    @Test
    void sendMessagePreservesBodyAndNegativeChatId() throws Exception {
        CapturingSender sender = new CapturingSender("""
                {"ok":true,"result":{"message_id":17,"date":1790985600,
                "chat":{"id":-1001234567890,"type":"supergroup","title":"Trading", "future_field":3},
                "from":{"id":4503599627370495,"is_bot":true,"first_name":"Trade"},
                "text":"  第一行\\nsecond line  ","entities":[],"future_field":true}}
                """, 200);
        String text = "  第一行\nsecond line  ";

        TelegramMessage message = api(sender).sendMessage("-1001234567890", text);

        JsonNode body = requestBody(sender.requests.getFirst());
        assertEquals("-1001234567890", body.path("chat_id").asText());
        assertEquals(text, body.path("text").asText());
        assertFalse(body.has("reply_markup"));
        assertEquals("/bot" + TEST_TOKEN + "/sendMessage", sender.requests.getFirst().uri().getPath());
        assertEquals(17L, message.messageId());
        assertEquals(1790985600L, message.date());
        assertEquals(-1001234567890L, message.chat().id());
        assertEquals("Trading", message.chat().title());
        assertEquals(4503599627370495L, message.from().id());
        assertEquals(text, message.text());
    }

    @Test
    void sendMessageSerializesInlineCallbackButtonsAsNestedJson() throws Exception {
        CapturingSender sender = new CapturingSender("""
                {"ok":true,"result":{"message_id":18,"chat":{"id":-1001234567890,"type":"supergroup"}}}
                """, 200);
        TelegramInlineKeyboardMarkup markup = new TelegramInlineKeyboardMarkup(List.of(
                List.of(new TelegramInlineKeyboardButton("  通过  ", "approve:post:42:v1"),
                        new TelegramInlineKeyboardButton("驳回", "reject:post:42:v1")),
                List.of(new TelegramInlineKeyboardButton("稍后", "later:post:42:v1"))));

        TelegramMessage message = api(sender).sendMessage("-1001234567890", "  待审核\n原文  ", markup);

        JsonNode body = requestBody(sender.requests.getFirst());
        assertEquals("  待审核\n原文  ", body.path("text").asText());
        JsonNode keyboard = body.path("reply_markup").path("inline_keyboard");
        assertTrue(keyboard.isArray());
        assertEquals(2, keyboard.size());
        assertEquals(2, keyboard.get(0).size());
        assertEquals("  通过  ", keyboard.get(0).get(0).path("text").asText());
        assertEquals("approve:post:42:v1", keyboard.get(0).get(0).path("callback_data").asText());
        assertEquals("reject:post:42:v1", keyboard.get(0).get(1).path("callback_data").asText());
        assertEquals("later:post:42:v1", keyboard.get(1).get(0).path("callback_data").asText());
        assertFalse(body.path("reply_markup").has("inlineKeyboard"));
        assertFalse(keyboard.get(0).get(0).has("callbackData"));
        assertEquals(18L, message.messageId());
        assertEquals(1, sender.requests.size());
    }

    @Test
    void inlineCallbackDataLimitsUseUtf8BytesAndKeepValuesUnchanged() {
        assertDoesNotThrow(() -> new TelegramInlineKeyboardButton("通过", "x".repeat(64)));
        assertDoesNotThrow(() -> new TelegramInlineKeyboardButton("通过", "😀".repeat(16)));
        assertDoesNotThrow(() -> new TelegramInlineKeyboardButton("通过", "审".repeat(21)));
        assertEquals(" ", new TelegramInlineKeyboardButton("通过", " ").callbackData());
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardButton("通过", "x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardButton("通过", "😀".repeat(17)));
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardButton("通过", "审".repeat(22)));
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardButton("通过", ""));
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardButton("通过", null));
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardButton(" ", "approve"));
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardButton(null, "approve"));
    }

    @Test
    void inlineKeyboardRejectsEmptyOrNullRowsAndKeepsImmutableSnapshot() {
        TelegramInlineKeyboardButton button = new TelegramInlineKeyboardButton("通过", "approve");
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardMarkup(null));
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardMarkup(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TelegramInlineKeyboardMarkup(List.of(List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramInlineKeyboardMarkup(Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramInlineKeyboardMarkup(List.of(Collections.singletonList(null))));
        List<TelegramInlineKeyboardButton> row = new ArrayList<>(List.of(button));
        List<List<TelegramInlineKeyboardButton>> rows = new ArrayList<>(List.of(row));

        TelegramInlineKeyboardMarkup markup = new TelegramInlineKeyboardMarkup(rows);
        row.clear();
        rows.clear();

        assertEquals(List.of(List.of(button)), markup.inlineKeyboard());
        assertThrows(UnsupportedOperationException.class, () -> markup.inlineKeyboard().clear());
        assertThrows(UnsupportedOperationException.class, () -> markup.inlineKeyboard().getFirst().clear());
    }

    @Test
    void sendMessageWithMarkupKeepsUnicodeTextLimitsAndDisabledGate() throws Exception {
        TelegramInlineKeyboardMarkup markup = new TelegramInlineKeyboardMarkup(
                List.of(List.of(new TelegramInlineKeyboardButton("通过", "approve"))));
        CapturingSender sender = new CapturingSender("""
                {"ok":true,"result":{"message_id":1,"chat":{"id":1,"type":"private"}}}
                """, 200);
        TelegramApi api = api(sender);
        String text = "😀".repeat(4096);

        api.sendMessage("1", text, markup);
        assertEquals(text, requestBody(sender.requests.getFirst()).path("text").asText());
        assertThrows(IllegalArgumentException.class, () -> api.sendMessage("1", text + "😀", markup));
        assertThrows(IllegalArgumentException.class, () -> api.sendMessage("1", "", markup));
        assertEquals(1, sender.requests.size());

        TelegramClientProperties disabled = new TelegramClientProperties();
        CapturingSender disabledSender = new CapturingSender("{}", 200);
        TelegramApi disabledApi = new TelegramApi(new TelegramHttpClient(disabled, disabledSender));
        assertThrows(IllegalStateException.class, () -> disabledApi.sendMessage("1", "draft", markup));
        assertTrue(disabledSender.requests.isEmpty());
    }

    @Test
    void sendMessageWithMarkupDoesNotRetryOrLeakSensitiveErrors() {
        TelegramInlineKeyboardMarkup markup = new TelegramInlineKeyboardMarkup(
                List.of(List.of(new TelegramInlineKeyboardButton("通过", "approve"))));
        CapturingSender sender = new CapturingSender("""
                {"ok":false,"error_code":429,"description":"invalid token: %s",
                "parameters":{"retry_after":8},"raw":"RAW_RESPONSE_SENTINEL"}
                """.formatted(TEST_TOKEN), 429);

        TelegramApiException error = assertThrows(TelegramApiException.class,
                () -> api(sender).sendMessage("1", "draft", markup));

        assertEquals(8, error.retryAfterSeconds());
        assertFalse(error.description().contains(TEST_TOKEN));
        assertFalse(stackTrace(error).contains(TEST_TOKEN));
        assertFalse(stackTrace(error).contains("RAW_RESPONSE_SENTINEL"));
        assertNull(error.getCause());
        assertEquals(1, sender.requests.size());
    }

    @Test
    void getUpdatesSendsExplicitOffsetAndExtendsLongPollingRequestTimeout() throws Exception {
        CapturingSender sender = new CapturingSender("{\"ok\":true,\"result\":[]}", 200);
        TelegramApi api = api(sender);

        assertTrue(api.getUpdates(51L, 25, 60, List.of("message", "callback_query")).isEmpty());

        HttpRequest request = sender.requests.getFirst();
        JsonNode body = requestBody(request);
        assertEquals(51L, body.path("offset").asLong());
        assertEquals(25, body.path("limit").asInt());
        assertEquals(60, body.path("timeout").asInt());
        assertEquals(List.of("message", "callback_query"), JSON.convertValue(body.path("allowed_updates"), List.class));
        assertEquals(Duration.ofSeconds(70), request.timeout().orElseThrow());
        assertEquals("POST", request.method());
        assertEquals("/bot" + TEST_TOKEN + "/getUpdates", request.uri().getPath());
    }

    @Test
    void getUpdatesKeepsConfiguredTimeoutAndDoesNotInventOffsetsOrAllowedUpdates() throws Exception {
        TelegramClientProperties properties = properties();
        properties.setRequestTimeoutSeconds(120);
        CapturingSender sender = new CapturingSender("{\"ok\":true,\"result\":[]}", 200);
        TelegramApi api = new TelegramApi(new TelegramHttpClient(properties, sender));

        api.getUpdates(null, 100, 15, null);
        api.getUpdates(null, 100, 15, List.of());

        JsonNode firstBody = requestBody(sender.requests.get(0));
        assertFalse(firstBody.has("offset"));
        assertFalse(firstBody.has("allowed_updates"));
        assertEquals(Duration.ofSeconds(120), sender.requests.get(0).timeout().orElseThrow());
        JsonNode secondBody = requestBody(sender.requests.get(1));
        assertFalse(secondBody.has("offset"));
        assertTrue(secondBody.path("allowed_updates").isArray());
        assertEquals(0, secondBody.path("allowed_updates").size());
    }

    @Test
    void ordinaryRequestsKeepShortConfiguredTimeoutWhilePollingRetainsBuffer() {
        TelegramClientProperties properties = properties();
        properties.setRequestTimeoutSeconds(5);
        CapturingSender ordinarySender = new CapturingSender(USER_RESPONSE, 200);
        TelegramApi ordinaryApi = new TelegramApi(new TelegramHttpClient(properties, ordinarySender));
        CapturingSender pollingSender = new CapturingSender("{\"ok\":true,\"result\":[]}", 200);
        TelegramApi pollingApi = new TelegramApi(new TelegramHttpClient(properties, pollingSender));

        ordinaryApi.getMe();
        pollingApi.getUpdates(null, 100, 0, null);

        assertEquals(Duration.ofSeconds(5), ordinarySender.requests.getFirst().timeout().orElseThrow());
        assertEquals(Duration.ofSeconds(10), pollingSender.requests.getFirst().timeout().orElseThrow());
    }

    @Test
    void getUpdatesMapsCallbacksAndLeavesAcknowledgementOffsetToCaller() throws Exception {
        CapturingSender sender = new CapturingSender("""
                {"ok":true,"result":[
                  {"update_id":61,"message":{"message_id":9,"date":1790985600,
                    "chat":{"id":4503599627370494,"type":"private"},"text":"/start"},"future_field":true},
                  {"update_id":62,"callback_query":{"id":"callback-62",
                    "from":{"id":4503599627370493,"is_bot":false,"first_name":"Alice"},
                    "message":{"message_id":10,"date":0,"chat":{"id":-1001234567890,"type":"supergroup"}},
                    "chat_instance":"instance-1","data":"approve:42","future_field":true}}
                ]}
                """, 200);
        TelegramApi api = api(sender);

        List<TelegramUpdate> updates = api.getUpdates(60L, 100, 0, List.of("message", "callback_query"));
        api.getUpdates(60L, 100, 0, List.of("message", "callback_query"));

        assertEquals(2, updates.size());
        assertEquals(61L, updates.get(0).updateId());
        assertEquals("/start", updates.get(0).message().text());
        assertNull(updates.get(0).callbackQuery());
        assertEquals(62L, updates.get(1).updateId());
        assertNull(updates.get(1).message());
        assertEquals("callback-62", updates.get(1).callbackQuery().id());
        assertEquals(4503599627370493L, updates.get(1).callbackQuery().from().id());
        assertEquals("approve:42", updates.get(1).callbackQuery().data());
        assertEquals("instance-1", updates.get(1).callbackQuery().chatInstance());
        assertEquals(0L, updates.get(1).callbackQuery().message().date());
        assertNull(updates.get(1).callbackQuery().message().text());
        assertEquals(60L, requestBody(sender.requests.get(1)).path("offset").asLong());
        assertEquals(2, sender.requests.size());
    }

    @Test
    void answerCallbackQueryUsesJsonAndAllowsSilentAcknowledgement() throws Exception {
        CapturingSender sender = new CapturingSender("{\"ok\":true,\"result\":true}", 200);
        TelegramApi api = api(sender);

        assertTrue(api.answerCallbackQuery("callback-1", null, false));
        assertTrue(api.answerCallbackQuery("callback-2", "  saved  ", true));

        JsonNode silentBody = requestBody(sender.requests.get(0));
        assertEquals("callback-1", silentBody.path("callback_query_id").asText());
        assertFalse(silentBody.path("show_alert").asBoolean());
        assertFalse(silentBody.has("text"));
        JsonNode secondBody = requestBody(sender.requests.get(1));
        assertEquals("  saved  ", secondBody.path("text").asText());
        assertTrue(secondBody.path("show_alert").asBoolean());
        assertEquals("/bot" + TEST_TOKEN + "/answerCallbackQuery", sender.requests.get(1).uri().getPath());
    }

    @Test
    void okFalseOnHttp200PreservesApiErrorAndMigrationTarget() {
        CapturingSender sender = new CapturingSender("""
                {"ok":false,"error_code":400,"description":"group migrated",
                 "parameters":{"migrate_to_chat_id":-1001234567890}}
                """, 200);

        TelegramApiException error = assertThrows(TelegramApiException.class, () -> api(sender).getMe());

        assertEquals(200, error.statusCode());
        assertEquals(400, error.errorCode());
        assertEquals("group migrated", error.description());
        assertEquals(-1001234567890L, error.migrateToChatId());
        assertNull(error.retryAfterSeconds());
        assertEquals(1, sender.requests.size());
    }

    @Test
    void http429PreservesRetryAfterWithoutRetryingSendMessage() {
        CapturingSender sender = new CapturingSender("""
                {"ok":false,"error_code":429,"description":"Too Many Requests",
                 "parameters":{"retry_after":8}}
                """, 429);

        TelegramApiException error = assertThrows(TelegramApiException.class,
                () -> api(sender).sendMessage("-1001234567890", "hello"));

        assertEquals(429, error.statusCode());
        assertEquals(429, error.errorCode());
        assertEquals(8, error.retryAfterSeconds());
        assertEquals(1, sender.requests.size());
    }

    @Test
    void malformedEnvelopeAndMissingResultCannotReportSuccess() {
        for (String body : List.of("<html>unavailable</html>", "{}", "{\"ok\":true}", "{\"ok\":true,\"result\":null}")) {
            CapturingSender sender = new CapturingSender(body, 200);

            assertThrows(IllegalStateException.class, () -> api(sender).getMe());
            assertEquals(1, sender.requests.size());
        }
    }

    @Test
    void disabledClientsStartWithoutTokenAndAllCallsRemainOffline() {
        TelegramClientProperties properties = new TelegramClientProperties();
        CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);
        TelegramHttpClient client = assertDoesNotThrow(() -> new TelegramHttpClient(properties, sender));
        TelegramApi api = new TelegramApi(client);

        assertThrows(IllegalStateException.class, api::getMe);
        assertThrows(IllegalStateException.class, () -> api.sendMessage("1", "hello"));
        assertThrows(IllegalStateException.class, () -> api.getUpdates(null, 100, 0, null));
        assertThrows(IllegalStateException.class, () -> api.answerCallbackQuery("callback-1", null, false));
        assertTrue(sender.requests.isEmpty());
    }

    @Test
    void enabledClientsWithMissingTokenRejectCallsBeforeSending() {
        TelegramClientProperties properties = new TelegramClientProperties();
        properties.setEnabled(true);
        CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);
        TelegramHttpClient client = assertDoesNotThrow(() -> new TelegramHttpClient(properties, sender));

        assertThrows(IllegalArgumentException.class, () -> new TelegramApi(client).getMe());
        assertTrue(sender.requests.isEmpty());
    }

    @Test
    void errorDescriptionsRedactTokensAndNeverExposeRawResponseBodies() {
        CapturingSender sender = new CapturingSender("""
                {"ok":false,"error_code":401,"description":"invalid token: %s",
                 "untrusted_body":"RAW_RESPONSE_SENTINEL %s"}
                """.formatted(TEST_TOKEN, TEST_TOKEN), 401);

        TelegramApiException error = assertThrows(TelegramApiException.class, () -> api(sender).getMe());

        assertFalse(error.description().contains(TEST_TOKEN));
        assertFalse(stackTrace(error).contains(TEST_TOKEN));
        assertFalse(stackTrace(error).contains("RAW_RESPONSE_SENTINEL"));
        assertNull(error.getCause());
    }

    @Test
    void malformedResponsesDoNotLeakRawBodyOrTokenThroughParsingCause() {
        CapturingSender sender = new CapturingSender("RAW_RESPONSE_SENTINEL " + TEST_TOKEN, 502);

        TelegramApiException error = assertThrows(TelegramApiException.class, () -> api(sender).getMe());

        assertEquals(502, error.statusCode());
        assertFalse(stackTrace(error).contains(TEST_TOKEN));
        assertFalse(stackTrace(error).contains("RAW_RESPONSE_SENTINEL"));
        assertNull(error.getCause());
    }

    @Test
    void ioFailuresHideRequestUrlAndCauseChainWithoutRetrying() {
        List<HttpRequest> requests = new ArrayList<>();
        TelegramHttpClient client = new TelegramHttpClient(properties(), request -> {
            requests.add(request);
            throw new IOException("failed " + request.uri(), new IOException("private " + TEST_TOKEN));
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new TelegramApi(client).sendMessage("1", "hello"));

        assertFalse(stackTrace(error).contains(TEST_TOKEN));
        assertNull(error.getCause());
        assertEquals(1, requests.size());
    }

    @Test
    void interruptedRequestsRestoreThreadInterruptFlagAndDoNotLeakToken() {
        TelegramHttpClient client = new TelegramHttpClient(properties(), request -> {
            throw new InterruptedException("failed " + request.uri());
        });

        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new TelegramApi(client).getMe());

            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(stackTrace(error).contains(TEST_TOKEN));
            assertNull(error.getCause());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidApiParametersAreRejectedBeforeAnyHttpCall() {
        CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);
        TelegramApi api = api(sender);

        assertThrows(IllegalArgumentException.class, () -> api.sendMessage(null, "hello"));
        assertThrows(IllegalArgumentException.class, () -> api.sendMessage(" ", "hello"));
        assertThrows(IllegalArgumentException.class, () -> api.sendMessage("1", ""));
        assertThrows(IllegalArgumentException.class, () -> api.sendMessage("1", "x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> api.getUpdates(null, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> api.getUpdates(null, 101, 0, null));
        assertThrows(IllegalArgumentException.class, () -> api.getUpdates(null, 100, -1, null));
        assertThrows(IllegalArgumentException.class, () -> api.answerCallbackQuery(" ", null, false));
        assertThrows(IllegalArgumentException.class, () -> api.answerCallbackQuery("callback-1", "x".repeat(201), false));
        assertTrue(sender.requests.isEmpty());
    }

    @Test
    void malformedMethodResultsCannotReportSuccessfulOperations() {
        assertThrows(IllegalStateException.class,
                () -> api(new CapturingSender("{\"ok\":true,\"result\":{}}", 200)).getMe());
        assertThrows(IllegalStateException.class,
                () -> api(new CapturingSender("{\"ok\":true,\"result\":{\"message_id\":1}}", 200)).sendMessage("1", "hello"));
        assertThrows(IllegalStateException.class,
                () -> api(new CapturingSender("{\"ok\":true,\"result\":{}}", 200)).getUpdates(null, 100, 0, null));
        assertThrows(IllegalStateException.class,
                () -> api(new CapturingSender("{\"ok\":true,\"result\":[{}]}", 200)).getUpdates(null, 100, 0, null));
        assertThrows(IllegalStateException.class,
                () -> api(new CapturingSender("{\"ok\":true,\"result\":false}", 200)).answerCallbackQuery("callback-1", null, false));
        assertThrows(IllegalStateException.class,
                () -> api(new CapturingSender("{\"ok\":true,\"result\":\"true\"}", 200)).answerCallbackQuery("callback-1", null, false));
    }

    @Test
    void unicodeCharacterLimitsDoNotTreatEmojiAsTwoCharacters() throws Exception {
        CapturingSender messageSender = new CapturingSender("""
                {"ok":true,"result":{"message_id":1,"date":1,"chat":{"id":1,"type":"private"}}}
                """, 200);
        String message = "😀".repeat(4096);

        assertDoesNotThrow(() -> api(messageSender).sendMessage("1", message));
        assertEquals(message, requestBody(messageSender.requests.getFirst()).path("text").asText());
        assertThrows(IllegalArgumentException.class, () -> api(messageSender).sendMessage("1", message + "😀"));
        assertEquals(1, messageSender.requests.size());

        CapturingSender callbackSender = new CapturingSender("{\"ok\":true,\"result\":true}", 200);
        assertTrue(api(callbackSender).answerCallbackQuery("callback-1", "😀".repeat(200), false));
        assertThrows(IllegalArgumentException.class,
                () -> api(callbackSender).answerCallbackQuery("callback-1", "😀".repeat(201), false));
        assertEquals(1, callbackSender.requests.size());
    }

    @Test
    void longestPollingTimeoutDoesNotOverflowRequestDeadline() {
        CapturingSender sender = new CapturingSender("{\"ok\":true,\"result\":[]}", 200);

        assertTrue(api(sender).getUpdates(null, 100, Integer.MAX_VALUE, null).isEmpty());

        assertEquals(Duration.ofSeconds((long) Integer.MAX_VALUE + 10), sender.requests.getFirst().timeout().orElseThrow());
    }

    private static TelegramApi api(CapturingSender sender) {
        return new TelegramApi(new TelegramHttpClient(properties(), sender));
    }

    private static TelegramClientProperties properties() {
        TelegramClientProperties properties = new TelegramClientProperties();
        properties.setEnabled(true);
        properties.setBotToken(TEST_TOKEN);
        return properties;
    }

    private static JsonNode requestBody(HttpRequest request) throws Exception {
        BodySubscriber subscriber = new BodySubscriber();
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        return JSON.readTree(subscriber.body());
    }

    private static String stackTrace(Throwable error) {
        StringWriter result = new StringWriter();
        error.printStackTrace(new PrintWriter(result));
        return result.toString();
    }

    private static final class CapturingSender implements TelegramHttpClient.Sender {
        private final String body;
        private final int statusCode;
        private final List<HttpRequest> requests = new ArrayList<>();

        private CapturingSender(String body, int statusCode) {
            this.body = body;
            this.statusCode = statusCode;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            requests.add(request);
            return new TestResponse(statusCode, body, request);
        }
    }

    private static final class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream result = new ByteArrayOutputStream();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            result.writeBytes(bytes);
        }

        @Override
        public void onError(Throwable throwable) {
            throw new IllegalStateException(throwable);
        }

        @Override
        public void onComplete() {
        }

        private String body() {
            return result.toString(StandardCharsets.UTF_8);
        }
    }

    private record TestResponse(int statusCode, String body, HttpRequest request) implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
