/**
 * Second-hand marketplace domain for authentication, listings, conversations,
 * messages, and constrained direct-to-OSS image upload forms.
 *
 * <p>HTTP requests enter through
 * {@link com.trade.marketplace.interfaces.web.MarketplaceController},
 * {@link com.trade.marketplace.interfaces.web.MarketplaceAuthController}, and
 * {@link com.trade.marketplace.interfaces.web.MarketplaceChatController}. Application
 * services own transactions and apply the account, listing, and conversation
 * rules in {@code domain.rule}. They access account, item, and conversation
 * storage through {@code marketplace.port}. MyBatis queries and row mapping
 * live in {@code infrastructure.persistence}. The direct-upload adapter lives in
 * {@code infrastructure.oss}.</p>
 */
package com.trade.marketplace;
