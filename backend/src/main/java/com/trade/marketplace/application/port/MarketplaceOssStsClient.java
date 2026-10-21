package com.trade.marketplace.application.port;

import java.util.Map;

/** Creates a constrained upload form without returning a signing secret to callers. */
public interface MarketplaceOssStsClient {
    UploadForm createUploadForm(UploadRequest request);

    record UploadRequest(
            String roleArn,
            String roleSessionName,
            String bucket,
            String objectKey,
            String region,
            String contentType,
            long sizeBytes,
            int durationSeconds
    ) {}

    record UploadForm(Map<String, String> formFields, String expiration) {}
}
