package com.trade.marketplace.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.marketplace.config.MarketplaceProperties;
import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.model.MarketplacePrincipal;
import com.trade.marketplace.oss.MarketplaceOssStsClient;
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
        MarketplaceUploadService service = new MarketplaceUploadService(properties, stsClient, new ObjectMapper());

        MarketplaceApi.UploadIntent intent = service.createIntent(
                new MarketplacePrincipal(42L, "alice", "Alice"),
                new MarketplaceApi.UploadIntentRequest("phone.png", "image/png")
        );

        assertEquals("bucket", intent.bucket());
        assertEquals("oss-cn-hangzhou", intent.region());
        assertTrue(intent.objectKey().startsWith("marketplace/users/42/"));
        assertTrue(intent.objectKey().endsWith(".png"));
        assertEquals("https://img.example.com/" + intent.objectKey(), intent.publicUrl());
        assertEquals("public-read", intent.objectAcl());
        assertEquals("sts-ak", intent.credentials().accessKeyId());
        assertTrue(stsClient.policy.contains("oss:PutObject"));
        assertTrue(stsClient.policy.contains("oss:PutObjectAcl"));
        assertTrue(stsClient.policy.contains("bucket/" + intent.objectKey()));
    }

    @Test
    void rejectsNonImageContentTypes() {
        MarketplaceUploadService service = new MarketplaceUploadService(
                new MarketplaceProperties(),
                new FakeStsClient(),
                new ObjectMapper()
        );

        assertThrows(IllegalArgumentException.class, () -> service.createIntent(
                new MarketplacePrincipal(1L, "alice", "Alice"),
                new MarketplaceApi.UploadIntentRequest("notes.txt", "text/plain")
        ));
    }

    private static class FakeStsClient implements MarketplaceOssStsClient {
        private String policy;

        @Override
        public MarketplaceApi.OssCredentials assumeRole(
                String roleArn,
                String roleSessionName,
                String policy,
                int durationSeconds
        ) {
            this.policy = policy;
            return new MarketplaceApi.OssCredentials(
                    "sts-ak",
                    "sts-sk",
                    "sts-token",
                    Instant.parse("2026-06-19T09:00:00Z")
            );
        }
    }
}
