package com.trade.marketplace.application;

import com.trade.marketplace.config.MarketplaceProperties;
import com.trade.marketplace.exception.MarketplaceConflictException;
import com.trade.marketplace.exception.MarketplaceUnauthorizedException;
import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.model.MarketplacePrincipal;
import com.trade.marketplace.persistence.MarketplaceMapper;
import com.trade.marketplace.persistence.MarketplaceSessionRow;
import com.trade.marketplace.persistence.MarketplaceUserRow;
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
import java.util.regex.Pattern;

/**
 * Registers users and manages opaque, expiring marketplace sessions.
 *
 * <p>Only token hashes are persisted; callers receive the raw bearer token
 * once when a session is issued.</p>
 */
@Service
public class MarketplaceAuthService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,32}");
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
        String username = cleanUsername(request == null ? null : request.username());
        String password = requiredPassword(request == null ? null : request.password());
        String displayName = cleanDisplayName(request == null ? null : request.displayName(), username);
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
        String username = cleanUsername(request == null ? null : request.username());
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
        mapper.touchSession(hash, Timestamp.from(now));
        return new MarketplacePrincipal(user.getId(), user.getUsername(), user.getDisplayName());
    }

    private static String cleanUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username must be 3-32 letters, numbers, dots, dashes, or underscores");
        }
        return username;
    }

    private static String requiredPassword(String value) {
        String password = value == null ? "" : value;
        if (password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password must be 8-128 characters");
        }
        return password;
    }

    private static String cleanDisplayName(String value, String fallback) {
        String displayName = value == null || value.isBlank() ? fallback : value.trim();
        if (displayName.length() > 40) {
            throw new IllegalArgumentException("displayName must be at most 40 characters");
        }
        return displayName;
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
