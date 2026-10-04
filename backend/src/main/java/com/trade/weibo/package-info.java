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
 *
 * <p>Reviewed posting uses the immutable domain post aggregate, application ports,
 * RSS/Atom and MyBatis adapters. AI prompt/validation belong to application.decision;
 * draft-review contracts, durable deliveries/cursors and authenticated Telegram adapters belong to this domain.
 * Telegram protocol lives in com.trade.client.telegram; config binds its transport settings.
 * Scheduler lifecycle is registered by automation; real publishing defaults off.</p>
 */
package com.trade.weibo;
