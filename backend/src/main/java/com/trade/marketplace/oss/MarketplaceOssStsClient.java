package com.trade.marketplace.oss;

import com.trade.marketplace.model.MarketplaceApi;

public interface MarketplaceOssStsClient {
    MarketplaceApi.OssCredentials assumeRole(
            String roleArn,
            String roleSessionName,
            String policy,
            int durationSeconds
    );
}
