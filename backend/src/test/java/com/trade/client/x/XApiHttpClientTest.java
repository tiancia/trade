package com.trade.client.x;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.x.dto.XPost;
import com.trade.client.x.dto.XUser;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class XApiHttpClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_RESPONSE = """
            {"data":{"id":"9999999999999999999","name":"Trade","username":"trade_test",
            "future_field":"ignored"},"meta":{"future":true}}
            """;

    @Test
    void getMeUsesSignedGetAndPreservesLargeStringIdentifiers() throws Exception {
        CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);

        XUser user = api(sender).getMe();

        assertEquals("9999999999999999999", user.id());
        assertEquals("Trade", user.name());
        assertEquals("trade_test", user.username());
        HttpRequest request = sender.requests.getFirst();
        assertEquals("GET", request.method());
        assertEquals(URI.create("https://api.x.com/2/users/me"), request.uri());
        assertTrue(request.bodyPublisher().isEmpty());
        assertEquals(Duration.ofSeconds(30), request.timeout().orElseThrow());
        verifyAuthorization(request);
        assertEquals(1, sender.requests.size());
    }

    @Test
    void publishTextSendsUnchangedJsonTextWithoutSigningItAsFormData() throws Exception {
        CapturingSender sender = new CapturingSender("""
                {"data":{"id":"1234567890123456789","text":"returned","future":true}}
                """, 201);
        String text = "  中文😀\nline + & % second  ";

        XPost post = api(sender).publishText(text);

        assertEquals("1234567890123456789", post.id());
        assertEquals("returned", post.text());
        HttpRequest request = sender.requests.getFirst();
        assertEquals("POST", request.method());
        assertEquals(URI.create("https://api.x.com/2/tweets"), request.uri());
        assertTrue(request.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        JsonNode body = JSON.readTree(body(request));
        assertEquals(1, body.size());
        assertEquals(text, body.path("text").asText());
        verifyAuthorization(request);
    }

    @Test
    void everyRequestUsesFreshNonceAndConfiguredDeadline() {
        XClientProperties properties = properties();
        properties.setRequestTimeoutSeconds(7);
        CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);
        XApi api = new XApi(new XHttpClient(properties, sender));

        api.getMe();
        api.getMe();

        assertNotEquals(oauth(sender.requests.get(0)).get("oauth_nonce"), oauth(sender.requests.get(1)).get("oauth_nonce"));
        assertEquals(Duration.ofSeconds(7), sender.requests.get(0).timeout().orElseThrow());
    }

    @Test
    void disabledClientStartsWithoutCredentialsAndNeverSendsEitherOperation() {
        CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);
        XClientProperties properties = new XClientProperties();
        XHttpClient http = assertDoesNotThrow(() -> new XHttpClient(properties, sender));
        XApi api = new XApi(http);

        assertThrows(IllegalStateException.class, api::getMe);
        assertThrows(IllegalStateException.class, () -> api.publishText("body"));
        assertTrue(sender.requests.isEmpty());
        assertSame(properties, http.properties());
    }

    @Test
    void eachMissingCredentialAndBlankTextFailsBeforeTransport() {
        for (int missing = 0; missing < 4; missing++) {
            XClientProperties properties = properties();
            switch (missing) {
                case 0 -> properties.setApiKey("");
                case 1 -> properties.setApiSecret("");
                case 2 -> properties.setAccessToken("");
                case 3 -> properties.setAccessTokenSecret("");
            }
            CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);
            XApi api = new XApi(new XHttpClient(properties, sender));
            assertThrows(IllegalArgumentException.class, api::getMe);
            assertTrue(sender.requests.isEmpty());
        }
        CapturingSender sender = new CapturingSender(USER_RESPONSE, 200);
        assertThrows(IllegalArgumentException.class, () -> api(sender).publishText(null));
        assertThrows(IllegalArgumentException.class, () -> api(sender).publishText(" \n"));
        assertTrue(sender.requests.isEmpty());
    }

    @Test
    void httpFailuresNeverRetryOrRetainSensitiveResponseBody() {
        for (int status : List.of(302, 401, 403, 429, 500)) {
            CapturingSender sender = new CapturingSender("RAW_RESPONSE_SENTINEL api-secret token-secret", status);
            XApiException error = assertThrows(XApiException.class, () -> api(sender).publishText("body"));
            assertEquals(status, error.statusCode());
            assertNull(error.getCause());
            assertFalse(trace(error).contains("RAW_RESPONSE_SENTINEL"));
            assertFalse(trace(error).contains("api-secret"));
            assertFalse(trace(error).contains("token-secret"));
            assertEquals(1, sender.requests.size());
        }
    }

    @Test
    void malformedEnvelopeOrIdentifierCannotClaimPublicationSuccess() {
        for (String response : List.of("RAW_RESPONSE_SENTINEL api-secret", "{}", "{\"data\":null}",
                "{\"data\":[]}", "{\"data\":{}}", "{\"data\":{\"id\":\"\"}}", "{\"data\":{\"id\":\"-12\"}}",
                "{\"data\":{\"id\":\"12345678901234567890\"}}", "{\"data\":{\"id\":\"abc\"}}",
                "{\"data\":{\"id\":1}}", "{\"data\":{\"id\":\"0\"}}")) {
            CapturingSender sender = new CapturingSender(response, 201);
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> api(sender).publishText("body"));
            assertNull(error.getCause());
            assertFalse(trace(error).contains("RAW_RESPONSE_SENTINEL"));
            assertFalse(trace(error).contains("api-secret"));
        }
        CapturingSender partialError = new CapturingSender("{\"data\":{\"id\":\"1\"},\"errors\":[{\"detail\":\"private\"}]}", 201);
        assertThrows(XApiException.class, () -> api(partialError).publishText("body"));
    }

    @Test
    void ioAndDecodeFailuresHideUnderlyingCausesAndDoNotRetry() {
        List<HttpRequest> requests = new ArrayList<>();
        XApi api = new XApi(new XHttpClient(properties(), request -> {
            requests.add(request);
            throw new IOException("private authorization " + request.headers(), new IOException("api-secret"));
        }));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> api.publishText("body"));
        assertNull(error.getCause());
        assertFalse(trace(error).contains("oauth_token"));
        assertFalse(trace(error).contains("api-secret"));
        assertEquals(1, requests.size());
        CapturingSender invalidDto = new CapturingSender("{\"data\":{\"id\":\"1\",\"name\":{\"secret\":\"api-secret\"}}}", 200);
        error = assertThrows(IllegalStateException.class, () -> api(invalidDto).getMe());
        assertNull(error.getCause());
        assertFalse(trace(error).contains("api-secret"));
    }

    @Test
    void interruptionRestoresFlagAndHidesAuthorizationFromException() {
        XApi api = new XApi(new XHttpClient(properties(), request -> {
            throw new InterruptedException("private " + request.headers());
        }));
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class, api::getMe);
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(error.getCause());
            assertFalse(trace(error).contains("oauth_token"));
        } finally {
            Thread.interrupted();
        }
    }

    private static void verifyAuthorization(HttpRequest request) throws Exception {
        Map<String, String> oauth = oauth(request);
        assertEquals("api-key", oauth.get("oauth_consumer_key"));
        assertEquals("access-token", oauth.get("oauth_token"));
        assertEquals("HMAC-SHA1", oauth.get("oauth_signature_method"));
        assertEquals("1.0", oauth.get("oauth_version"));
        assertTrue(oauth.get("oauth_nonce").matches("[0-9a-f]{32}"));
        assertTrue(Long.parseLong(oauth.get("oauth_timestamp")) > 0);
        // Independent fixed-order verification: these endpoints have only six OAuth parameters, no JSON fields.
        String parameters = "oauth_consumer_key=api-key&oauth_nonce=" + oauth.get("oauth_nonce")
                + "&oauth_signature_method=HMAC-SHA1&oauth_timestamp=" + oauth.get("oauth_timestamp")
                + "&oauth_token=access-token&oauth_version=1.0";
        String base = request.method() + "&" + URLEncoder.encode(request.uri().toString(), StandardCharsets.UTF_8)
                + "&" + URLEncoder.encode(parameters, StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("api-secret&token-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8))), oauth.get("oauth_signature"));
    }

    private static Map<String, String> oauth(HttpRequest request) {
        String authorization = request.headers().firstValue("Authorization").orElseThrow();
        assertTrue(authorization.startsWith("OAuth "));
        Matcher matcher = Pattern.compile("(oauth_[a-z_]+)=\"([^\"]*)\"").matcher(authorization);
        Map<String, String> values = new LinkedHashMap<>();
        while (matcher.find()) values.put(matcher.group(1), URLDecoder.decode(matcher.group(2), StandardCharsets.UTF_8));
        return values;
    }

    private static XApi api(CapturingSender sender) {
        return new XApi(new XHttpClient(properties(), sender));
    }

    private static XClientProperties properties() {
        XClientProperties properties = new XClientProperties();
        properties.setEnabled(true);
        properties.setApiKey("api-key");
        properties.setApiSecret("api-secret");
        properties.setAccessToken("access-token");
        properties.setAccessTokenSecret("token-secret");
        return properties;
    }

    private static String trace(Throwable error) {
        StringWriter result = new StringWriter();
        error.printStackTrace(new PrintWriter(result));
        return result.toString();
    }

    private static String body(HttpRequest request) {
        BodySubscriber subscriber = new BodySubscriber();
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        return subscriber.bytes.toString(StandardCharsets.UTF_8);
    }

    private static class CapturingSender implements XHttpClient.Sender {
        private final String body;
        private final int status;
        private final List<HttpRequest> requests = new ArrayList<>();
        CapturingSender(String body, int status) { this.body = body; this.status = status; }
        public HttpResponse<String> send(HttpRequest request) {
            requests.add(request);
            return new Response(status, body, request);
        }
    }

    private static class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        public void onNext(ByteBuffer item) {
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            bytes.writeBytes(chunk);
        }
        public void onError(Throwable error) { throw new IllegalStateException(error); }
        public void onComplete() { }
    }

    private record Response(int statusCode, String body, HttpRequest request) implements HttpResponse<String> {
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
