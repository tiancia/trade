package com.trade.marketplace.application.service;

import com.trade.marketplace.application.port.MarketplaceOssStsClient;
import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.infrastructure.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceUploadServiceTest {
    @Test
    void createsSingleObjectUploadIntent() {
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

        assertEquals("bucket", intent.bucket());
        assertEquals("oss-cn-hangzhou", intent.region());
        assertTrue(intent.objectKey().startsWith("marketplace/users/42/"));
        assertTrue(intent.objectKey().endsWith(".png"));
        assertEquals("https://img.example.com/" + intent.objectKey(), intent.publicUrl());
        assertEquals("public-read", intent.objectAcl());
        assertEquals("sts-ak", intent.credentials().accessKeyId());
        assertEquals("bucket", stsClient.bucket);
        assertEquals(intent.objectKey(), stsClient.objectKey);
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

    private static class FakeStsClient implements MarketplaceOssStsClient {
        private String bucket;
        private String objectKey;

        @Override
        public MarketplaceApi.OssCredentials assumeUploadRole(
                String roleArn,
                String roleSessionName,
                String bucket,
                String objectKey,
                int durationSeconds
        ) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            return new MarketplaceApi.OssCredentials(
                    "sts-ak",
                    "sts-sk",
                    "sts-token",
                    Instant.parse("2026-06-19T09:00:00Z")
            );
        }
    }
}
