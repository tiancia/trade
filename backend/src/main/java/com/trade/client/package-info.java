/**
 * Transport-focused clients for external services.
 *
 * <p>Keep HTTP signing, request construction, DTO mapping, WebSocket plumbing,
 * and provider-specific defaults here. Shared provider selection belongs in
 * {@code config}; business workflows call client contracts from a domain
 * service instead of embedding transport details.</p>
 */
package com.trade.client;
