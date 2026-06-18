/**
 * Weibo account authorization and publishing domain.
 *
 * <p>OAuth state, account tokens, and publish requests are separated from the
 * generic Weibo HTTP client so the domain can enforce admin-token checks and
 * token persistence rules.</p>
 */
package com.trade.weibo;
