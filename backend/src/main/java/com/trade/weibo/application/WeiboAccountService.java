package com.trade.weibo.application;

import com.trade.weibo.application.port.WeiboAccountTokenRepository;
import com.trade.weibo.model.WeiboAccount;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Resolves the current Weibo account without exposing the stored access token.
 */
@Service
public class WeiboAccountService {
    private final WeiboAccountTokenRepository tokenRepository;
    private final Clock clock;

    @Autowired
    public WeiboAccountService(WeiboAccountTokenRepository tokenRepository) {
        this(tokenRepository, Clock.systemUTC());
    }

    public WeiboAccountService(WeiboAccountTokenRepository tokenRepository, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public WeiboAccount currentAccount() {
        Instant now = Instant.now(clock);
        return tokenRepository.findCurrent()
                .map(token -> new WeiboAccount(
                        token.uid(),
                        token.expiresAt() != null && token.expiresAt().isAfter(now),
                        token.expiresAt()
                ))
                .orElseGet(() -> new WeiboAccount(null, false, null));
    }
}
