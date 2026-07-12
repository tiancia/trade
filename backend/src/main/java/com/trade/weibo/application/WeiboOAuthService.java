package com.trade.weibo.application;

import com.trade.client.weibo.WeiboAccessToken;
import com.trade.client.weibo.WeiboApi;
import com.trade.weibo.application.port.WeiboAccountTokenRepository;
import com.trade.weibo.application.port.WeiboOAuthStateRepository;
import com.trade.weibo.model.WeiboAuthorization;
import com.trade.weibo.model.WeiboAuthorizeUrl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Coordinates the complete Weibo OAuth flow.
 *
 * <p>It creates a short-lived state, consumes that state exactly once during
 * the callback, exchanges the code through the transport client, and stores
 * the resulting account token through application ports.</p>
 */
@Service
public class WeiboOAuthService {
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WeiboApi api;
    private final WeiboOAuthStateRepository stateRepository;
    private final WeiboAccountTokenRepository tokenRepository;
    private final Clock clock;

    @Autowired
    public WeiboOAuthService(
            WeiboApi api,
            WeiboOAuthStateRepository stateRepository,
            WeiboAccountTokenRepository tokenRepository
    ) {
        this(api, stateRepository, tokenRepository, Clock.systemUTC());
    }

    public WeiboOAuthService(
            WeiboApi api,
            WeiboOAuthStateRepository stateRepository,
            WeiboAccountTokenRepository tokenRepository,
            Clock clock
    ) {
        this.api = api;
        this.stateRepository = stateRepository;
        this.tokenRepository = tokenRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public WeiboAuthorizeUrl createAuthorizeUrl() {
        String state = newState();
        Instant expiresAt = Instant.now(clock).plus(STATE_TTL);
        stateRepository.save(state, expiresAt);
        return new WeiboAuthorizeUrl(api.buildAuthorizeUrl(state), state, expiresAt);
    }

    @Transactional
    public WeiboAuthorization handleCallback(String code, String state) {
        String cleanCode = requiredText(code, "code is required");
        String cleanState = requiredText(state, "state is required");
        Instant now = Instant.now(clock);
        if (!stateRepository.consume(cleanState, now)) {
            throw new WeiboOAuthException("OAuth state is invalid, expired, or already used");
        }

        WeiboAccessToken token = api.exchangeCode(cleanCode);
        String accessToken = requiredText(token.accessToken(), "Weibo access token is missing");
        String uid = api.getUid(accessToken);
        Instant expiresAt = now.plusSeconds(Math.max(0, token.expiresInSeconds()));
        tokenRepository.upsert(uid, accessToken, expiresAt);
        return new WeiboAuthorization(uid, expiresAt, true);
    }

    private static String newState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
