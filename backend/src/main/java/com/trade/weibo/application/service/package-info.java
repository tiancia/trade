/**
 * Weibo use cases for OAuth authorization, account inspection, and publishing.
 *
 * <p>Services depend on transport clients and application-owned ports, never on
 * MyBatis implementations. Shared use-case failures live in
 * {@code com.trade.weibo.domain.exception}; return values live in {@code com.trade.weibo.domain.model}.</p>
 */
package com.trade.weibo.application.service;
