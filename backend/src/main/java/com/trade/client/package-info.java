/**
 * Transport-focused clients grouped by external service or capability.
 *
 * <p>Keep HTTP signing, request construction, DTO mapping, WebSocket plumbing,
 * and provider-specific defaults here. AI clients live in {@code ai}, with
 * provider selection in {@code ai.config} and Gemini transport in {@code ai.gemini};
 * business workflows call client contracts from a domain
 * service instead of embedding transport details.</p>
 */
package com.trade.client;
