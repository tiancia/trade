package com.trade.x.infrastructure.publisher;

import com.trade.client.x.XApi;
import com.trade.client.x.XClientProperties;
import com.trade.x.application.decision.XPostTextValidator;
import com.trade.x.application.port.XPostPublisher;
import com.trade.x.domain.exception.XPublishingException;
import com.trade.x.domain.model.XPost;
import com.trade.x.domain.model.XPostStatus;
import com.trade.x.domain.model.XWorkflowPolicy;
import com.trade.x.infrastructure.config.XPublishingProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;
import java.time.Instant;

/** Checks the token owner before each POST; no create-post retry, including after a timeout. */
@Component
public class XApiPostPublisher implements XPostPublisher {
    private final XApi api;
    private final XClientProperties client;
    private final XPublishingProperties settings;
    private final XWorkflowPolicy policy;
    private final Clock clock;

    @Autowired
    public XApiPostPublisher(XApi api, XClientProperties client, XPublishingProperties settings, XWorkflowPolicy policy) {
        this(api, client, settings, policy, Clock.systemUTC());
    }

    public XApiPostPublisher(XApi api, XClientProperties client, XPublishingProperties settings, XWorkflowPolicy policy,
            Clock clock) {
        this.api = api;
        this.client = client;
        this.settings = settings;
        this.policy = policy;
        this.clock = clock;
    }

    @Override public boolean credentialsAvailable(String targetUserId) {
        if (!client.isEnabled() || !settings.isLivePublishingEnabled() || !policy.enabled()
                || !policy.publishingEnabled() || !policy.targetUserId().equals(targetUserId)) return false;
        try {
            client.requiredApiKey(); client.requiredApiSecret();
            client.requiredAccessToken(); client.requiredAccessTokenSecret();
            return true;
        } catch (IllegalArgumentException missing) { return false; }
    }

    @Override public String publish(XPost claimed) {
        try {
            policy.requirePublishing();
            if (!credentialsAvailable(claimed.targetUserId()) || claimed.status() != XPostStatus.PUBLISHING
                    || claimed.reviewer() == null || claimed.reviewedAt() == null || claimed.attemptId() == null) {
                throw new XPublishingException("X publishing gates or review are incomplete");
            }
            String validated = XPostTextValidator.normalizeAndValidate(claimed.body(), claimed.contentPolicy());
            if (!validated.equals(claimed.body())) throw new XPublishingException("X reviewed text is not canonical");
            if (!claimed.targetUserId().equals(api.getMe().id())) {
                throw new XPublishingException("X access token belongs to a different target account");
            }
            if (!claimed.live(Instant.now(clock))) throw new XPublishingException("X draft expired before send");
        } catch (XPublishingException blocked) { throw blocked; }
        catch (RuntimeException blocked) { throw new XPublishingException("X publishing preflight failed before send"); }
        return api.publishText(claimed.body()).id();
    }
}
