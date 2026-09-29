package com.trade.marketplace.application.service;

import com.trade.marketplace.domain.rule.MarketplaceAccountRules;
import com.trade.marketplace.domain.exception.MarketplaceConflictException;
import com.trade.marketplace.domain.exception.MarketplaceUnauthorizedException;
import com.trade.marketplace.domain.model.MarketplaceApi;
import com.trade.marketplace.domain.model.MarketplacePrincipal;
import com.trade.marketplace.infrastructure.config.MarketplaceProperties;
import com.trade.marketplace.infrastructure.persistence.MarketplaceMapper;
import com.trade.marketplace.infrastructure.persistence.MarketplaceSessionRow;
import com.trade.marketplace.infrastructure.persistence.MarketplaceUserRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Registers users and manages opaque, expiring marketplace sessions.
 *
 * <p>Only token hashes are persisted; callers receive the raw bearer token
 * once when a session is issued.</p>
 */
@Service
public class MarketplaceAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MarketplaceMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final MarketplaceProperties properties;
    private final Clock clock;

    @Autowired
    public MarketplaceAuthService(
            MarketplaceMapper mapper,
            PasswordEncoder passwordEncoder,
            MarketplaceProperties properties
    ) {
        this(mapper, passwordEncoder, properties, Clock.systemUTC());
    }

    public MarketplaceAuthService(
            MarketplaceMapper mapper,
            PasswordEncoder passwordEncoder,
            MarketplaceProperties properties,
            Clock clock
    ) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Transactional
    public MarketplaceApi.AuthResponse register(MarketplaceApi.RegisterRequest request) {
        String username = MarketplaceAccountRules.cleanUsername(request == null ? null : request.username());
        String password = MarketplaceAccountRules.requiredPassword(request == null ? null : request.password());
        String displayName = MarketplaceAccountRules.cleanDisplayName(request == null ? null : request.displayName(), username);
        MarketplaceUserRow row = new MarketplaceUserRow()
                .setUsername(username)
                .setPasswordHash(passwordEncoder.encode(password))
                .setDisplayName(displayName);
        try {
            mapper.insertUser(row);
        } catch (DuplicateKeyException e) {
            throw new MarketplaceConflictException("username is already registered");
        }
        return issueSession(row);
    }

    @Transactional
    public MarketplaceApi.AuthResponse login(MarketplaceApi.LoginRequest request) {
        String username = MarketplaceAccountRules.cleanUsername(request == null ? null : request.username());
        String password = request == null || request.password() == null ? "" : request.password();
        MarketplaceUserRow row = mapper.findUserByUsername(username);
        if (row == null || !passwordEncoder.matches(password, row.getPasswordHash())) {
            throw new MarketplaceUnauthorizedException("username or password is invalid");
        }
        return issueSession(row);
    }

    @Transactional
    public void logout(String authorization) {
        String token = bearerToken(authorization, true);
        mapper.revokeSession(hashToken(token), Timestamp.from(Instant.now(clock)));
    }

    public MarketplaceApi.User me(String authorization) {
        return MarketplaceViews.user(requireUser(authorization));
    }

    public MarketplacePrincipal optionalUser(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        return requireToken(bearerToken(authorization, true));
    }

    public MarketplacePrincipal requireUser(String authorization) {
        return requireToken(bearerToken(authorization, true));
    }

    private MarketplaceApi.AuthResponse issueSession(MarketplaceUserRow user) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(Math.max(1, properties.getSessionRetentionDays()), ChronoUnit.DAYS);
        String token = newToken();
        mapper.insertSession(new MarketplaceSessionRow()
                .setTokenHash(hashToken(token))
                .setUserId(user.getId())
                .setExpiresAt(Timestamp.from(expiresAt))
                .setLastSeenAt(Timestamp.from(now)));
        return new MarketplaceApi.AuthResponse(token, MarketplaceViews.user(user), expiresAt);
    }

    private MarketplacePrincipal requireToken(String token) {
        Instant now = Instant.now(clock);
        String hash = hashToken(token);
        MarketplaceSessionRow session = mapper.findSessionByTokenHash(hash);
        if (session == null || session.getRevokedAt() != null || session.getExpiresAt().toInstant().isBefore(now)) {
            throw new MarketplaceUnauthorizedException("marketplace session is invalid or expired");
        }
        MarketplaceUserRow user = mapper.findUserById(session.getUserId());
        if (user == null) {
            throw new MarketplaceUnauthorizedException("marketplace session user no longer exists");
        }
        Instant touchBefore = now.minus(
                Math.max(1, properties.getSessionTouchIntervalMinutes()),
                ChronoUnit.MINUTES
        );
        if (session.getLastSeenAt() == null || session.getLastSeenAt().toInstant().isBefore(touchBefore)) {
            mapper.touchSession(hash, Timestamp.from(now));
        }
        return new MarketplacePrincipal(user.getId(), user.getUsername(), user.getDisplayName());
    }

    private static String bearerToken(String authorization, boolean required) {
        if (authorization == null || authorization.isBlank()) {
            if (required) {
                throw new MarketplaceUnauthorizedException("Authorization bearer token is required");
            }
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new MarketplaceUnauthorizedException("Authorization bearer token is required");
        }
        String token = authorization.substring(prefix.length()).trim();
        if (token.isBlank()) {
            throw new MarketplaceUnauthorizedException("Authorization bearer token is required");
        }
        return token;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
