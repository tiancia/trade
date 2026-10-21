package com.trade.marketplace.infrastructure.oss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.marketplace.infrastructure.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.HashSet;
import java.time.Instant;
import java.util.Base64;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.trade.marketplace.application.port.MarketplaceOssStsClient.UploadRequest;
import com.trade.marketplace.domain.exception.MarketplaceUnavailableException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliyunMarketplaceOssStsClientTest {
    @Test
    void v4SignatureMatchesAnIndependentKnownVector() throws Exception {
        // Generated independently with Python's hmac/hashlib using synthetic credentials.
        assertEquals("ed3b6207e84fdebe8f3a5b93bf3394b5c1b504ec6ea54cd4f660e9f61f818728",
                AliyunMarketplaceOssStsClient.postSignature("test-secret", "20261021", "cn-hangzhou",
                        "eyJleHBpcmF0aW9uIjoiMjAyNi0xMC0yMVQwODowNTowMFoifQ=="));
    }

    @Test
    void uploadPolicyGrantsOnlySingleObjectUploadActions() throws Exception {
        var mapper = new ObjectMapper();
        var client = new AliyunMarketplaceOssStsClient(new MarketplaceProperties(), mapper);
        var policy = mapper.readTree(client.uploadPolicy("bucket", "marketplace/users/42/image.png"));
        assertEquals("1", policy.path("Version").asText());
        assertEquals(1, policy.path("Statement").size());
        var statement = policy.path("Statement").get(0);
        assertEquals("Allow", statement.path("Effect").asText());
        assertEquals(1, statement.path("Resource").size());
        assertEquals("acs:oss:*:*:bucket/marketplace/users/42/image.png", statement.path("Resource").get(0).asText());
        Set<String> actions = new HashSet<>();
        statement.path("Action").forEach(action -> actions.add(action.asText()));
        assertEquals(Set.of("oss:PutObject", "oss:PutObjectAcl"), actions);
    }

    @Test
    void signedFormBindsObjectTypeSizeAndAllFieldsWithoutExposingTheSecret() throws Exception {
        var mapper = new ObjectMapper();
        var client = new AliyunMarketplaceOssStsClient(new MarketplaceProperties(), mapper);
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        var credentials = new AliyunMarketplaceOssStsClient.StsCredentials("test-ak", "test-secret", "test-token", now.plusSeconds(900));
        var request = new UploadRequest("role", "session", "bucket", "marketplace/users/42/image.png",
                "cn-hangzhou", "image/png", 1024L, 900);
        var form = client.signedForm(credentials, request, now);
        var fields = form.formFields();
        var policy = mapper.readTree(Base64.getDecoder().decode(fields.get("policy")));
        assertEquals("2026-10-21T08:05:00Z", policy.path("expiration").asText());
        assertEquals("2026-10-21T08:05:00Z", form.expiration());
        assertEquals("OSS4-HMAC-SHA256", fields.get("x-oss-signature-version"));
        assertEquals("test-ak/20261021/cn-hangzhou/oss/aliyun_v4_request", fields.get("x-oss-credential"));
        assertEquals("20261021T080000Z", fields.get("x-oss-date"));
        assertEquals("public-read", fields.get("x-oss-object-acl"));
        assertEquals("204", fields.get("success_action_status"));
        assertEquals("image/png", fields.get("content-type"));
        assertEquals("image/png", fields.get("x-oss-content-type"));
        var conditions = policy.path("conditions");
        for (var entry : fields.entrySet()) {
            if (Set.of("policy", "x-oss-signature").contains(entry.getKey())) continue;
            boolean bound = false;
            for (var condition : conditions) {
                if (condition.isObject() && condition.path(entry.getKey()).asText().equals(entry.getValue())) bound = true;
            }
            assertTrue(bound, () -> "Unbound upload field: " + entry.getKey());
        }
        assertTrue(conditions.toString().contains("[\"content-length-range\",1024,1024]"));
        assertTrue(conditions.toString().contains("\"bucket\":\"bucket\""));
        String serialized = mapper.writeValueAsString(form);
        assertFalse(serialized.contains("test-secret"));
        assertFalse(serialized.contains("accessKeySecret"));
        assertTrue(fields.get("x-oss-signature").matches("[0-9a-f]{64}"));
    }

    @Test
    void uploadPolicyNeverOutlivesTheStsCredentials() throws Exception {
        var client = new AliyunMarketplaceOssStsClient(new MarketplaceProperties(), new ObjectMapper());
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        var request = new UploadRequest("role", "session", "bucket", "image.png", "cn-hangzhou", "image/png", 1L, 900);
        var shortLived = new AliyunMarketplaceOssStsClient.StsCredentials("ak", "secret", "token", now.plusSeconds(10));
        assertEquals(now.plusSeconds(10).toString(), client.signedForm(shortLived, request, now).expiration());
        var expired = new AliyunMarketplaceOssStsClient.StsCredentials("ak", "secret", "token", now);
        assertThrows(MarketplaceUnavailableException.class, () -> client.signedForm(expired, request, now));
    }

    @Test
    void failedStsRequestsCannotReturnAnUploadFormOrExposeSigningSecrets() throws Exception {
        var client = clientReturning(503, "{\"Code\":\"ServiceUnavailable\",\"Message\":\"Try again later\"}");
        var failure = assertThrows(MarketplaceUnavailableException.class, () -> client.createUploadForm(uploadRequest()));
        assertTrue(failure.getMessage().contains("503"));
        assertNoSigningSecrets(failure);
    }

    @Test
    void malformedOrIncompleteStsCredentialsCannotProduceAnUploadSignature() throws Exception {
        for (String body : new String[]{
                "not-json",
                "{\"RequestId\":\"test-request\"}",
                """
                {"Credentials":{"AccessKeyId":"temporary-ak","AccessKeySecret":"temporary-secret",
                  "Expiration":"2099-01-01T00:00:00Z"}}
                """,
                credentialsBody("not-an-instant")
        }) {
            var client = clientReturning(200, body);
            var failure = assertThrows(MarketplaceUnavailableException.class, () -> client.createUploadForm(uploadRequest()));
            assertNoSigningSecrets(failure);
        }
    }

    @Test
    void expiredCredentialsAreRejectedThroughTheRealUploadFormEntryPoint() throws Exception {
        var client = clientReturning(200, credentialsBody("1970-01-01T00:00:00Z"));
        var failure = assertThrows(MarketplaceUnavailableException.class, () -> client.createUploadForm(uploadRequest()));
        assertTrue(failure.getMessage().contains("expired"));
        assertNoSigningSecrets(failure);
    }

    private static UploadRequest uploadRequest() {
        return new UploadRequest("role", "session", "bucket", "marketplace/users/42/image.png",
                "cn-hangzhou", "image/png", 1024L, 900);
    }

    @SuppressWarnings("unchecked")
    private static AliyunMarketplaceOssStsClient clientReturning(int status, String body) throws Exception {
        var properties = new MarketplaceProperties();
        properties.getOss().setAccessKeyId("long-term-ak");
        properties.getOss().setAccessKeySecret("long-term-secret");
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return new AliyunMarketplaceOssStsClient(properties, new ObjectMapper(), http);
    }

    private static String credentialsBody(String expiration) {
        return """
                {"Credentials":{"AccessKeyId":"temporary-ak","AccessKeySecret":"temporary-secret",
                  "SecurityToken":"temporary-token","Expiration":"%s"}}
                """.formatted(expiration);
    }

    private static void assertNoSigningSecrets(MarketplaceUnavailableException failure) {
        assertFalse(failure.getMessage().contains("long-term-secret"));
        assertFalse(failure.getMessage().contains("temporary-secret"));
        assertFalse(failure.getMessage().contains("temporary-token"));
    }
}
