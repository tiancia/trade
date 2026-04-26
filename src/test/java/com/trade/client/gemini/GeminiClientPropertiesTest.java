package com.trade.client.gemini;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiClientPropertiesTest {
    @Test
    void proxyIsDisabledByDefault() {
        HttpClient client = GeminiClient.buildHttpClient(new GeminiClientProperties());

        assertTrue(client.proxy().isEmpty());
    }

    @Test
    void proxyUsesConfiguredHostAndPortWhenEnabled() {
        GeminiClientProperties properties = new GeminiClientProperties();
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost("10.0.0.3");
        properties.getProxy().setPort(19090);

        HttpClient client = GeminiClient.buildHttpClient(properties);
        List<Proxy> proxies = client.proxy().orElseThrow().select(URI.create("https://generativelanguage.googleapis.com"));
        InetSocketAddress address = (InetSocketAddress) proxies.getFirst().address();

        assertEquals("10.0.0.3", address.getHostString());
        assertEquals(19090, address.getPort());
    }
}
