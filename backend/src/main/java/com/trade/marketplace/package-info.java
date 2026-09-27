/**
 * Second-hand marketplace domain for authentication, listings, conversations,
 * messages, and direct-to-OSS image upload credentials.
 *
 * <p>HTTP requests enter through
 * {@link com.trade.marketplace.interfaces.web.MarketplaceController},
 * {@link com.trade.marketplace.interfaces.web.MarketplaceAuthController}, and
 * {@link com.trade.marketplace.interfaces.web.MarketplaceChatController}. Application
 * services own validation and use-case
 * orchestration under {@code application.service}; {@code domain} contains values
 * and shared exceptions. {@code infrastructure.persistence} contains MyBatis rows
 * and mappers, while {@code infrastructure.oss} implements the outbound OSS port.</p>
 */
package com.trade.marketplace;
