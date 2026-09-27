/**
 * Weibo account authorization and publishing domain.
 *
 * <p>{@link com.trade.weibo.interfaces.web.WeiboController} delegates OAuth and publishing
 * flows to {@link com.trade.weibo.application.service.WeiboOAuthService} and
 * {@link com.trade.weibo.application.service.WeiboPublishingService}; application ports
 * isolate use cases from the
 * MyBatis adapters in {@code infrastructure.persistence}; public values live in
 * {@code domain.model}, and shared OAuth/publishing failures live in {@code domain.exception}.
 * The generic HTTP transport remains in
 * {@code com.trade.client.weibo}.</p>
 */
package com.trade.weibo;
