package com.trade.weibo;

import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboPublishResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class WeiboPublishingService {
    private final WeiboApi api;
    private final WeiboAccountTokenRepository tokenRepository;
    private final Clock clock;

    @Autowired
    public WeiboPublishingService(WeiboApi api, WeiboAccountTokenRepository tokenRepository) {
        this(api, tokenRepository, Clock.systemUTC());
    }

    public WeiboPublishingService(WeiboApi api, WeiboAccountTokenRepository tokenRepository, Clock clock) {
        this.api = api;
        this.tokenRepository = tokenRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public WeiboPublishResult publishText(String status) {
        String cleanStatus = requiredText(status, "status is required");
        WeiboAccountToken token = tokenRepository.findValid(Instant.now(clock))
                .orElseThrow(() -> new WeiboPublishingException("No valid Weibo access token is available"));
        return api.publishText(token.accessToken(), cleanStatus);
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
