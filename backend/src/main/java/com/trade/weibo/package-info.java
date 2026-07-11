/**
 * Weibo account authorization and publishing domain.
 *
 * <p>{@link com.trade.weibo.web.WeiboController} delegates OAuth and publishing
 * flows to {@link com.trade.weibo.application.WeiboOAuthService} and
 * {@link com.trade.weibo.application.WeiboPublishingService}; application ports
 * isolate use cases from the
 * MyBatis adapters in {@code persistence}; public values live in
 * {@code model}. The generic HTTP transport remains in
 * {@code com.trade.client.weibo}.</p>
 */
package com.trade.weibo;
