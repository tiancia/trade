package com.trade.marketplace.application.port;

import com.trade.marketplace.domain.model.MarketplaceApi;

public interface MarketplaceOssStsClient {
    MarketplaceApi.OssCredentials assumeUploadRole(
            String roleArn,
            String roleSessionName,
            String bucket,
            String objectKey,
            int durationSeconds
    );
}
