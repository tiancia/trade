package com.trade.marketplace.port;

import com.trade.marketplace.domain.model.MarketplacePrincipal;

import java.time.Instant;
import java.util.Optional;

public interface MarketplaceAccountStore {
    record Credentials(MarketplacePrincipal principal, String passwordHash) {}
    record Session(long userId, Instant expiresAt, Instant revokedAt, Instant lastSeenAt) {}

    MarketplacePrincipal createUser(String username, String passwordHash, String displayName);

    Optional<Credentials> findCredentials(String username);

    Optional<MarketplacePrincipal> findPrincipal(long userId);

    void issueSession(String tokenHash, long userId, Instant expiresAt, Instant lastSeenAt);

    Optional<Session> findSession(String tokenHash);

    void revokeSession(String tokenHash, Instant revokedAt);

    void touchSession(String tokenHash, Instant lastSeenAt);
}
