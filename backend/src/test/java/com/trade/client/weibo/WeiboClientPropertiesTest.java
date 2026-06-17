package com.trade.client.weibo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeiboClientPropertiesTest {
    @Test
    void normalizesBaseUrlsAndRequiredCredentials() {
        WeiboClientProperties properties = new WeiboClientProperties();
        properties.setClientId(" app-key ");
        properties.setClientSecret(" app-secret ");
        properties.setRedirectUri(" https://example.test/weibo/callback ");
        properties.setOauthBaseUrl("https://api.weibo.com/oauth2///");
        properties.setApiBaseUrl("https://api.weibo.com/2/");

        assertEquals("app-key", properties.requiredClientId());
        assertEquals("app-secret", properties.requiredClientSecret());
        assertEquals("https://example.test/weibo/callback", properties.requiredRedirectUri());
        assertEquals("https://api.weibo.com/oauth2", properties.normalizedOauthBaseUrl());
        assertEquals("https://api.weibo.com/2", properties.normalizedApiBaseUrl());
    }

    @Test
    void rejectsBlankRequiredValues() {
        WeiboClientProperties properties = new WeiboClientProperties();

        assertThrows(IllegalArgumentException.class, properties::requiredClientId);
        assertThrows(IllegalArgumentException.class, properties::requiredClientSecret);
        assertThrows(IllegalArgumentException.class, properties::requiredRedirectUri);
    }

    @Test
    void exposesProxyConfiguration() {
        WeiboClientProperties properties = new WeiboClientProperties();
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost("127.0.0.1");
        properties.getProxy().setPort(7897);

        assertEquals(true, properties.getProxy().isEnabled());
        assertEquals("127.0.0.1", properties.getProxy().getHost());
        assertEquals(7897, properties.getProxy().getPort());
    }
}
