package com.trade.client.x;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class XOAuth1SignerTest {
    @Test
    void signatureMatchesOfficialXPublishedHmacSha1Vector() {
        // Public, revoked example credentials from the official X signature guide.
        Map<String, String> parameters = Map.of(
                "oauth_consumer_key", "xvz1evFS4wEEPTGEFPHBog",
                "oauth_nonce", "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg",
                "oauth_signature_method", "HMAC-SHA1",
                "oauth_timestamp", "1318622958",
                "oauth_token", "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb",
                "oauth_version", "1.0",
                "status", "Hello Ladies + Gentlemen, a signed OAuth request!");

        String signature = XOAuth1Signer.signature("POST",
                URI.create("https://api.x.com/1.1/statuses/update.json?include_entities=true"), parameters,
                "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw",
                "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE");

        assertEquals("Ls93hJiZbQ3akF3HF3x1Bz8/zU4=", signature);
    }

    @Test
    void percentEncodingFollowsRfc5849Utf8AndUnreservedCharacters() {
        assertEquals("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~",
                XOAuth1Signer.encode("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"));
        assertEquals("%20%2B%2A%25%2F%26%3D", XOAuth1Signer.encode(" +*%/&="));
        assertEquals("%E4%B8%AD%F0%9F%98%80", XOAuth1Signer.encode("中😀"));
    }

    @Test
    void baseUriNormalizesCaseAndDefaultPortButSignsAllDecodedQueryPairs() {
        Map<String, String> oauth = Map.of("oauth_consumer_key", "key", "oauth_nonce", "nonce");
        String expected = XOAuth1Signer.signature("GET", URI.create("https://api.x.com/2/users/me?a=x%20y&a=1&b=%2B"),
                oauth, "secret&/", "token secret");

        assertEquals(expected, XOAuth1Signer.signature("get",
                URI.create("HTTPS://API.X.COM:443/2/users/me?b=%2B&a=1&a=x+y#ignored"), oauth, "secret&/", "token secret"));
        assertNotEquals(expected, XOAuth1Signer.signature("GET", URI.create("https://api.x.com/2/users/me?a=1&b=%2B"),
                oauth, "secret&/", "token secret"));
        assertNotEquals(expected, XOAuth1Signer.signature("POST", URI.create("https://api.x.com/2/users/me?a=x%20y&a=1&b=%2B"),
                oauth, "secret&/", "token secret"));
        assertNotEquals(expected, XOAuth1Signer.signature("GET", URI.create("https://api.x.com:8443/2/users/me?a=x%20y&a=1&b=%2B"),
                oauth, "secret&/", "token secret"));
    }
}
