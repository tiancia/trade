package com.trade.weibo.application.service;

import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboPublishResult;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.weibo.application.port.WeiboPostPublisher;
import com.trade.weibo.application.port.WeiboAccountTokenRepository;
import com.trade.weibo.domain.exception.WeiboPublishingException;
import com.trade.weibo.domain.model.WeiboAccountToken;
import com.trade.weibo.domain.model.WeiboPost;
import com.trade.weibo.domain.model.WeiboPostStatus;
import com.trade.weibo.domain.model.WeiboWorkflowPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Publishes text behind live/review gates, binding workflow posts to their exact account.
 *
 * <p>Credential selection stays behind the repository port so this use case
 * does not depend on MyBatis or database row objects.</p>
 */
@Service
public class WeiboPublishingService implements WeiboPostPublisher {
    private final WeiboApi api;
    private final WeiboAccountTokenRepository tokenRepository;
    private final Clock clock;
    private final WeiboClientProperties properties;
    private final WeiboWorkflowPolicy policy;

    @Autowired
    public WeiboPublishingService(WeiboApi api, WeiboAccountTokenRepository tokenRepository,
                                  WeiboClientProperties properties, WeiboWorkflowPolicy policy) {
        this(api, tokenRepository, Clock.systemUTC(), properties, policy);
    }

    public WeiboPublishingService(WeiboApi api, WeiboAccountTokenRepository tokenRepository, Clock clock,
                                  WeiboClientProperties properties, WeiboWorkflowPolicy policy) {
        this.api = api;
        this.tokenRepository = tokenRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.properties = properties;
        this.policy = policy;
    }

    public WeiboPublishResult publishText(String status) {
        String cleanStatus = requiredText(status, "status is required");
        requireLivePublishing();
        if (properties.isReviewRequired() || policy.enabled()) {
            throw new WeiboPublishingException("Direct publishing is disabled; use the reviewed post workflow");
        }
        WeiboAccountToken token = tokenRepository.findValid(Instant.now(clock))
                .orElseThrow(() -> new WeiboPublishingException("No valid Weibo access token is available"));
        return api.publishText(token.accessToken(), cleanStatus);
    }

    @Override
    public boolean credentialsAvailable(String uid) {
        return properties.isLivePublishingEnabled() && tokenRepository.findValidForUid(uid, Instant.now(clock)).isPresent();
    }

    @Override
    public String publish(WeiboPost claimed) {
        requireLivePublishing();
        policy.requirePublishing();
        if (claimed.status() != WeiboPostStatus.PUBLISHING || claimed.reviewedAt() == null
                || claimed.reviewer() == null || claimed.attemptId() == null
                || !claimed.live(Instant.now(clock))
                || !claimed.event().occurredAt().plus(policy.eventMaxAge()).isAfter(Instant.now(clock))
                || !policy.targetUid().equals(claimed.targetUid())) {
            throw new WeiboPublishingException("Post is not eligible for publishing");
        }
        WeiboAccountToken token = tokenRepository.findValidForUid(claimed.targetUid(), Instant.now(clock))
                .orElseThrow(() -> new WeiboPublishingException("Target Weibo account requires authorization"));
        try { claimed.validatePublishingBody(policy.maxBodyChars()); }
        catch (IllegalArgumentException e) { throw new WeiboPublishingException("Post body no longer meets publishing limits"); }
        return api.publishText(token.accessToken(), requiredText(claimed.body(), "status is required")).id();
    }

    private void requireLivePublishing() {
        if (!properties.isLivePublishingEnabled()) throw new WeiboPublishingException("Live Weibo publishing is disabled");
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
