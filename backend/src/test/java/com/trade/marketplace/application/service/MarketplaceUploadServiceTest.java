package com.trade.marketplace.application.service;

import com.trade.marketplace.port.MarketplaceOssStsClient;
import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.infrastructure.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceUploadServiceTest {
    @Test
    void createsSingleObjectUploadIntent() throws Exception {
        MarketplaceProperties properties = new MarketplaceProperties();
        properties.getOss().setAccessKeyId("ak");
        properties.getOss().setAccessKeySecret("sk");
        properties.getOss().setRoleArn("acs:ram::1:role/upload");
        properties.getOss().setBucket("bucket");
        properties.getOss().setRegion("oss-cn-hangzhou");
        properties.getOss().setPublicBaseUrl("https://img.example.com/");
        FakeStsClient stsClient = new FakeStsClient();
        MarketplaceUploadService service = new MarketplaceUploadService(properties, stsClient);

        MarketplaceApi.UploadIntent intent = service.createIntent(
                new MarketplacePrincipal(42L, "alice", "Alice"),
                new MarketplaceApi.UploadIntentRequest("phone.png", "image/png", 1024L)
        );

        assertEquals("https://bucket.oss-cn-hangzhou.aliyuncs.com", intent.uploadUrl());
        assertTrue(intent.objectKey().startsWith("marketplace/users/42/"));
        assertTrue(intent.objectKey().endsWith(".png"));
        assertEquals("https://img.example.com/" + intent.objectKey(), intent.publicUrl());
        assertEquals("signed-policy", intent.formFields().get("policy"));
        assertEquals("2026-10-21T09:00:00Z", intent.expiration());
        String json = new ObjectMapper().writeValueAsString(intent);
        assertTrue(!json.contains("credentials") && !json.contains("accessKeySecret"));
        assertEquals("bucket", stsClient.bucket);
        assertEquals(intent.objectKey(), stsClient.objectKey);
        assertEquals("cn-hangzhou", stsClient.request.region());
        assertEquals("image/png", stsClient.request.contentType());
        assertEquals(1024L, stsClient.request.sizeBytes());
    }

    @Test
    void rejectsNonImageContentTypes() {
        MarketplaceUploadService service = new MarketplaceUploadService(
                new MarketplaceProperties(),
                new FakeStsClient()
        );

        assertThrows(IllegalArgumentException.class, () -> service.createIntent(
                new MarketplacePrincipal(1L, "alice", "Alice"),
                new MarketplaceApi.UploadIntentRequest("notes.txt", "text/plain", 1024L)
        ));
    }

    @Test
    void rejectsEmptyAndOversizedImages() {
        MarketplaceUploadService service = new MarketplaceUploadService(
                new MarketplaceProperties(),
                new FakeStsClient()
        );
        MarketplacePrincipal user = new MarketplacePrincipal(1L, "alice", "Alice");

        assertThrows(IllegalArgumentException.class, () -> service.createIntent(
                user,
                new MarketplaceApi.UploadIntentRequest("empty.png", "image/png", 0L)
        ));
        assertThrows(IllegalArgumentException.class, () -> service.createIntent(
                user,
                new MarketplaceApi.UploadIntentRequest("large.png", "image/png", 10L * 1024 * 1024 + 1)
        ));
    }

    @Test
    void regionAliasesUseTheSameUploadEndpointAndSigningScope() {
        for (String region : new String[]{"cn-hangzhou", "oss-cn-hangzhou"}) {
            var properties = new MarketplaceProperties();
            properties.getOss().setRoleArn("acs:ram::1:role/upload");
            properties.getOss().setBucket("bucket");
            properties.getOss().setRegion(region);
            var stsClient = new FakeStsClient();
            var service = new MarketplaceUploadService(properties, stsClient);
            var intent = service.createIntent(new MarketplacePrincipal(42L, "alice", "Alice"),
                    new MarketplaceApi.UploadIntentRequest("image.png", "image/png", 1024L));

            assertEquals("cn-hangzhou", stsClient.request.region());
            assertEquals("https://bucket.oss-cn-hangzhou.aliyuncs.com", intent.uploadUrl());
            assertEquals(intent.uploadUrl() + "/" + intent.objectKey(), intent.publicUrl());
        }
    }

    private static class FakeStsClient implements MarketplaceOssStsClient {
        private String bucket;
        private String objectKey;
        private UploadRequest request;

        @Override
        public UploadForm createUploadForm(UploadRequest request) {
            this.request = request;
            this.bucket = request.bucket();
            this.objectKey = request.objectKey();
            return new UploadForm(Map.of("policy", "signed-policy"), "2026-10-21T09:00:00Z");
        }
    }
}
