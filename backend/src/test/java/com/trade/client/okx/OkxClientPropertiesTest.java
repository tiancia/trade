package com.trade.client.okx;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxClientPropertiesTest {
    @Test
    void proxyIsDisabledByDefault() {
        HttpClient client = OkxClient.buildHttpClient(new OkxClientProperties());

        assertTrue(client.proxy().isEmpty());
    }

    @Test
    void proxyUsesConfiguredHostAndPortWhenEnabled() {
        OkxClientProperties properties = new OkxClientProperties();
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost("10.0.0.2");
        properties.getProxy().setPort(18080);

        HttpClient client = OkxClient.buildHttpClient(properties);
        List<Proxy> proxies = client.proxy().orElseThrow().select(URI.create("https://www.okx.com"));
        InetSocketAddress address = (InetSocketAddress) proxies.getFirst().address();

        assertEquals("10.0.0.2", address.getHostString());
        assertEquals(18080, address.getPort());
    }
}
