package com.trade.client.x;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class XClientPropertiesTest {
    @Test
    void defaultsAreDisabledAndFourCredentialsNeverAppearInToString() {
        XClientProperties properties = new XClientProperties();
        assertFalse(properties.isEnabled());
        assertEquals("https://api.x.com", properties.normalizedBaseUrl());
        assertEquals(10, properties.getConnectTimeoutSeconds());
        assertEquals(30, properties.getRequestTimeoutSeconds());
        assertFalse(properties.getProxy().isEnabled());
        assertEquals(7897, properties.getProxy().getPort());
        properties.setApiKey("KEY_SENTINEL");
        properties.setApiSecret("SECRET_SENTINEL");
        properties.setAccessToken("TOKEN_SENTINEL");
        properties.setAccessTokenSecret("TOKEN_SECRET_SENTINEL");
        for (String sentinel : List.of("KEY_SENTINEL", "SECRET_SENTINEL", "TOKEN_SENTINEL", "TOKEN_SECRET_SENTINEL")) {
            assertFalse(properties.toString().contains(sentinel));
        }
    }

    @Test
    void endpointNormalizationRejectsCredentialsQueriesAndFragmentsWithoutEchoingInput() {
        XClientProperties properties = new XClientProperties();
        properties.setBaseUrl(" https://api.x.com/// ");
        assertEquals("https://api.x.com", properties.normalizedBaseUrl());
        for (String value : List.of("ftp://example.test/PRIVATE", "https://PRIVATE@example.test", "https://example.test?PRIVATE",
                "https://example.test/#PRIVATE", "https://example.test/PRIVATE value", "PRIVATE")) {
            properties.setBaseUrl(value);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, properties::normalizedBaseUrl);
            assertFalse(error.getMessage().contains("PRIVATE"));
            assertNull(error.getCause());
        }
    }

    @Test
    void transportNeverFollowsRedirectsAndOnlyEnablesExplicitProxy() {
        XClientProperties properties = new XClientProperties();
        HttpClient direct = XHttpClient.buildHttpClient(properties);
        assertEquals(HttpClient.Redirect.NEVER, direct.followRedirects());
        assertEquals(Duration.ofSeconds(10), direct.connectTimeout().orElseThrow());
        assertTrue(direct.proxy().isEmpty());
        properties.getProxy().setEnabled(true);
        Proxy proxy = XHttpClient.buildHttpClient(properties).proxy().orElseThrow()
                .select(URI.create("https://api.x.com/2/users/me")).getFirst();
        assertEquals(Proxy.Type.HTTP, proxy.type());
        assertEquals(new InetSocketAddress("127.0.0.1", 7897), proxy.address());
        properties.getProxy().setPort(0);
        assertThrows(IllegalArgumentException.class, () -> XHttpClient.buildHttpClient(properties));
        properties.getProxy().setEnabled(false);
        properties.setConnectTimeoutSeconds(0);
        assertThrows(IllegalArgumentException.class, () -> XHttpClient.buildHttpClient(properties));
    }
}
