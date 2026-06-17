package com.trade.client.weibo;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeiboApiHttpClientTest {
    @Test
    void exchangeCodeUsesFormBody() {
        CapturingSender sender = new CapturingSender("""
                {"access_token":"token-value","expires_in":3600}
                """, 200);
        WeiboApi api = new WeiboApi(new WeiboHttpClient(properties(), sender));

        WeiboAccessToken token = api.exchangeCode("callback-code");

        assertEquals("token-value", token.accessToken());
        assertEquals(3600, token.expiresInSeconds());
        assertEquals("POST", sender.request.method());
        assertEquals(URI.create("https://api.weibo.com/oauth2/access_token"), sender.request.uri());
        assertEquals(Optional.of("application/x-www-form-urlencoded"), sender.request.headers().firstValue("Content-Type"));
        String body = body(sender.request);
        assertTrue(body.contains("client_id=client-id"));
        assertTrue(body.contains("client_secret=client-secret"));
        assertTrue(body.contains("grant_type=authorization_code"));
        assertTrue(body.contains("code=callback-code"));
        assertTrue(body.contains("redirect_uri=https%3A%2F%2Fexample.test%2Fcallback"));
    }

    @Test
    void publishTextUsesFormBodyWithAccessTokenAndStatus() {
        CapturingSender sender = new CapturingSender("""
                {"id":123,"mid":"456","created_at":"Tue Jun 16 23:00:00 +0800 2026","text":"hello"}
                """, 200);
        WeiboApi api = new WeiboApi(new WeiboHttpClient(properties(), sender));

        WeiboPublishResult result = api.publishText("token-value", "hello world");

        assertEquals("123", result.id());
        assertEquals("456", result.mid());
        assertEquals("Tue Jun 16 23:00:00 +0800 2026", result.createdAt());
        assertEquals(URI.create("https://api.weibo.com/2/statuses/update.json"), sender.request.uri());
        String body = body(sender.request);
        assertTrue(body.contains("access_token=token-value"));
        assertTrue(body.contains("status=hello+world"));
    }

    @Test
    void httpErrorPreservesStatusAndBodyWithoutRequestSecret() {
        CapturingSender sender = new CapturingSender("{\"error\":\"bad auth\"}", 400);
        WeiboApi api = new WeiboApi(new WeiboHttpClient(properties(), sender));

        WeiboHttpException error = assertThrows(WeiboHttpException.class, () -> api.exchangeCode("callback-code"));

        assertEquals(400, error.statusCode());
        assertEquals("{\"error\":\"bad auth\"}", error.responseBody());
        assertFalse(error.getMessage().contains("client-secret"));
    }

    @Test
    void httpErrorRedactsAccessTokenFromUri() {
        CapturingSender sender = new CapturingSender("{\"error\":\"expired token\"}", 401);
        WeiboApi api = new WeiboApi(new WeiboHttpClient(properties(), sender));

        WeiboHttpException error = assertThrows(WeiboHttpException.class, () -> api.getUid("secret-token"));

        assertEquals(401, error.statusCode());
        assertFalse(error.getMessage().contains("secret-token"));
        assertTrue(error.getMessage().contains("access_token=***"));
    }

    @Test
    void getUidUsesJsonGet() {
        CapturingSender sender = new CapturingSender("{\"uid\":\"12345\"}", 200);
        WeiboApi api = new WeiboApi(new WeiboHttpClient(properties(), sender));

        assertEquals("12345", api.getUid("token-value"));
        assertEquals("GET", sender.request.method());
        assertEquals("https://api.weibo.com/2/account/get_uid.json?access_token=token-value", sender.request.uri().toString());
    }

    private static WeiboClientProperties properties() {
        WeiboClientProperties properties = new WeiboClientProperties();
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setRedirectUri("https://example.test/callback");
        return properties;
    }

    private static String body(HttpRequest request) {
        BodySubscriber subscriber = new BodySubscriber();
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        return subscriber.body();
    }

    private static final class CapturingSender implements WeiboHttpClient.Sender {
        private final String body;
        private final int statusCode;
        private HttpRequest request;

        private CapturingSender(String body, int statusCode) {
            this.body = body;
            this.statusCode = statusCode;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new TestResponse(statusCode, body, request);
        }
    }

    private static final class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final StringBuilder builder = new StringBuilder();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            builder.append(StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(Throwable throwable) {
            throw new RuntimeException(throwable);
        }

        @Override
        public void onComplete() {
        }

        private String body() {
            return builder.toString();
        }
    }

    private record TestResponse(int statusCode, String body, HttpRequest request) implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public String body() {
            return body;
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
