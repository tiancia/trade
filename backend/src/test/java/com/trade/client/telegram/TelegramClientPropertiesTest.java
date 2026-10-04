package com.trade.client.telegram;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramClientPropertiesTest {
    @Test
    void defaultsDisableTelegramAndPermitClientConstructionWithoutToken() {
        TelegramClientProperties properties = new TelegramClientProperties();

        assertFalse(properties.isEnabled());
        assertEquals("", properties.getBotToken());
        assertEquals("https://api.telegram.org", properties.getBaseUrl());
        assertEquals(10, properties.getConnectTimeoutSeconds());
        assertEquals(30, properties.getRequestTimeoutSeconds());
        assertFalse(properties.getProxy().isEnabled());
        assertEquals("127.0.0.1", properties.getProxy().getHost());
        assertEquals(7897, properties.getProxy().getPort());
        TelegramHttpClient client = assertDoesNotThrow(() -> new TelegramHttpClient(properties));
        assertSame(properties, client.properties());
    }

    @Test
    void normalizesBaseUrlAndRequiredTokenWithoutIncludingTokenInToString() {
        TelegramClientProperties properties = new TelegramClientProperties();
        properties.setBaseUrl(" https://api.telegram.org/// ");
        properties.setBotToken(" 123456:test-secret-token ");

        assertEquals("https://api.telegram.org", properties.normalizedBaseUrl());
        assertEquals("123456:test-secret-token", properties.requiredBotToken());
        assertFalse(properties.toString().contains("test-secret-token"));
    }

    @Test
    void invalidTokensAreRejectedWithoutEchoingTheirContents() {
        TelegramClientProperties properties = new TelegramClientProperties();
        for (String token : new String[]{null, "", " ", "secret/token", "secret?token", "secret\ntoken"}) {
            properties.setBotToken(token);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, properties::requiredBotToken);

            assertFalse(error.getMessage().contains("secret"));
        }
    }

    @Test
    void rejectsBaseUrlsWithCredentialsQueriesFragmentsOrMissingHttpHost() {
        TelegramClientProperties properties = new TelegramClientProperties();
        for (String url : new String[]{null, "", " ", "api.telegram.org", "file:///tmp/telegram",
                "https://user:secret@example.test", "https://api.telegram.org?token=secret",
                "https://api.telegram.org#secret", "https:///telegram"}) {
            properties.setBaseUrl(url);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, properties::normalizedBaseUrl);

            assertFalse(error.getMessage().contains("secret"));
        }
    }

    @Test
    void proxyConfigurationBuildsExpectedHttpProxyAndConnectTimeout() {
        TelegramClientProperties properties = new TelegramClientProperties();
        properties.setConnectTimeoutSeconds(7);
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost(" 127.0.0.1 ");
        properties.getProxy().setPort(7897);

        HttpClient client = TelegramHttpClient.buildHttpClient(properties);
        Proxy proxy = client.proxy().orElseThrow().select(URI.create("https://api.telegram.org")).getFirst();

        assertEquals(Duration.ofSeconds(7), client.connectTimeout().orElseThrow());
        assertEquals(Proxy.Type.HTTP, proxy.type());
        assertEquals(new InetSocketAddress("127.0.0.1", 7897), proxy.address());
    }

    @Test
    void disabledProxyDoesNotInstallProxySelector() {
        TelegramClientProperties properties = new TelegramClientProperties();

        assertFalse(TelegramHttpClient.buildHttpClient(properties).proxy().isPresent());
    }

    @Test
    void invalidConnectTimeoutAndEnabledProxySettingsFailBeforeNetworkUse() {
        TelegramClientProperties properties = new TelegramClientProperties();
        properties.setConnectTimeoutSeconds(0);
        assertThrows(IllegalArgumentException.class, () -> TelegramHttpClient.buildHttpClient(properties));

        properties.setConnectTimeoutSeconds(10);
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost(" ");
        assertThrows(IllegalArgumentException.class, () -> TelegramHttpClient.buildHttpClient(properties));

        properties.getProxy().setHost("127.0.0.1");
        for (int port : new int[]{0, -1, 65536}) {
            properties.getProxy().setPort(port);
            assertThrows(IllegalArgumentException.class, () -> TelegramHttpClient.buildHttpClient(properties));
        }
    }
}
