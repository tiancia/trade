package com.trade.client.polymarket;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymarketClientPropertiesTest {
    @Test
    void normalizesBaseUrls() {
        PolymarketClientProperties properties = new PolymarketClientProperties();
        properties.setGammaBaseUrl("https://gamma-api.polymarket.com///");
        properties.setClobBaseUrl("https://clob.polymarket.com/");

        assertEquals("https://gamma-api.polymarket.com", properties.normalizedGammaBaseUrl());
        assertEquals("https://clob.polymarket.com", properties.normalizedClobBaseUrl());
    }

    @Test
    void proxyUsesConfiguredHostAndPortWhenEnabled() {
        PolymarketClientProperties properties = new PolymarketClientProperties();
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost("10.1.2.3");
        properties.getProxy().setPort(18080);

        HttpClient client = PolymarketHttpClient.buildHttpClient(properties);
        List<Proxy> proxies = client.proxy().orElseThrow().select(URI.create("https://clob.polymarket.com"));
        InetSocketAddress address = (InetSocketAddress) proxies.getFirst().address();

        assertEquals("10.1.2.3", address.getHostString());
        assertEquals(18080, address.getPort());
    }

    @Test
    void proxyIsDisabledByDefault() {
        HttpClient client = PolymarketHttpClient.buildHttpClient(new PolymarketClientProperties());

        assertTrue(client.proxy().isEmpty());
    }
}
