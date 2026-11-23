package com.trade.marketplace.infrastructure.persistence;

import com.trade.marketplace.domain.exception.MarketplaceConflictException;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.port.MarketplaceAccountStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class MyBatisMarketplaceAccountStore implements MarketplaceAccountStore {
    private final MarketplaceMapper mapper;

    public MyBatisMarketplaceAccountStore(MarketplaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MarketplacePrincipal createUser(String username, String passwordHash, String displayName) {
        MarketplaceUserRow row = new MarketplaceUserRow()
                .setUsername(username).setPasswordHash(passwordHash).setDisplayName(displayName);
        try {
            mapper.insertUser(row);
        } catch (DuplicateKeyException e) {
            throw new MarketplaceConflictException("username is already registered");
        }
        return principal(row);
    }

    @Override
    public Optional<Credentials> findCredentials(String username) {
        return Optional.ofNullable(mapper.findUserByUsername(username))
                .map(row -> new Credentials(principal(row), row.getPasswordHash()));
    }

    @Override
    public Optional<MarketplacePrincipal> findPrincipal(long userId) {
        return Optional.ofNullable(mapper.findUserById(userId)).map(MyBatisMarketplaceAccountStore::principal);
    }

    @Override
    public void issueSession(String tokenHash, long userId, Instant expiresAt, Instant lastSeenAt) {
        mapper.insertSession(new MarketplaceSessionRow()
                .setTokenHash(tokenHash).setUserId(userId)
                .setExpiresAt(Timestamp.from(expiresAt)).setLastSeenAt(Timestamp.from(lastSeenAt)));
    }

    @Override
    public Optional<Session> findSession(String tokenHash) {
        return Optional.ofNullable(mapper.findSessionByTokenHash(tokenHash))
                .map(row -> new Session(row.getUserId(), row.getExpiresAt().toInstant(),
                        MarketplaceViews.instant(row.getRevokedAt()), MarketplaceViews.instant(row.getLastSeenAt())));
    }

    @Override
    public void revokeSession(String tokenHash, Instant revokedAt) {
        mapper.revokeSession(tokenHash, Timestamp.from(revokedAt));
    }

    @Override
    public void touchSession(String tokenHash, Instant lastSeenAt) {
        mapper.touchSession(tokenHash, Timestamp.from(lastSeenAt));
    }

    private static MarketplacePrincipal principal(MarketplaceUserRow row) {
        return new MarketplacePrincipal(row.getId(), row.getUsername(), row.getDisplayName());
    }
}
