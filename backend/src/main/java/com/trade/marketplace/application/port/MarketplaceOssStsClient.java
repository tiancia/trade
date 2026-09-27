package com.trade.marketplace.application.port;

import com.trade.marketplace.domain.model.MarketplaceApi;

public interface MarketplaceOssStsClient {
    MarketplaceApi.OssCredentials assumeRole(
            String roleArn,
            String roleSessionName,
            String policy,
            int durationSeconds
    );
}
