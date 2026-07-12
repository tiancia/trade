/**
 * Second-hand marketplace domain for authentication, listings, conversations,
 * messages, and direct-to-OSS image upload credentials.
 *
 * <p>HTTP requests enter through
 * {@link com.trade.marketplace.web.MarketplaceController},
 * {@link com.trade.marketplace.web.MarketplaceAuthController}, and
 * {@link com.trade.marketplace.web.MarketplaceChatController}. Application
 * services own validation and use-case
 * orchestration, persistence contains MyBatis rows and mappers, while
 * {@code oss} is an outbound Aliyun adapter.</p>
 */
package com.trade.marketplace;
