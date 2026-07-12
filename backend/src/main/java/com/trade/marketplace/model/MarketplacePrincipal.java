package com.trade.marketplace.model;

/**
 * Authenticated marketplace identity passed between web and application layers.
 *
 * <p>This deliberately excludes password hashes, session hashes, timestamps,
 * and every other persistence-only field.</p>
 */
public record MarketplacePrincipal(Long id, String username, String displayName) {
}
