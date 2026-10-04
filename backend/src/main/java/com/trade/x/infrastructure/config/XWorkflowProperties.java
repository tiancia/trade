package com.trade.x.infrastructure.config;

import com.trade.x.domain.model.XContentPolicy;
import com.trade.x.domain.model.XWorkflowPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "trade.x.workflow")
public class XWorkflowProperties {
    private boolean enabled = false;
    private boolean generationEnabled = false;
    private boolean publishingEnabled = false;
    private String targetUserId = "";
    private Content content = new Content();
    private int dailyGenerationLimit = 5;
    private int dailyPublishLimit = 3;
    private long reviewTtlHours = 6;
    private long publishIntervalSeconds = 1800;
    private long claimTimeoutSeconds = 300;
    private long generationFixedDelayMs = 900000;
    private long publishingFixedDelayMs = 60000;
    private long initialDelayMs = 30000;

    @Data
    public static class Content {
        private String direction = "";
        private String language = "简体中文";
        private String tone = "清晰、自然、克制";
        private String instructions = "";
        private int minChars = 40;
        private int maxChars = 120;
        public XContentPolicy policy() {
            return new XContentPolicy(direction, language, tone, instructions, minChars, maxChars);
        }
    }

    public XWorkflowPolicy policy() {
        if (content == null || generationFixedDelayMs < 1000 || publishingFixedDelayMs < 1000 || initialDelayMs < 0) {
            throw new IllegalArgumentException("Invalid X workflow configuration or scheduler delays");
        }
        XWorkflowPolicy policy = new XWorkflowPolicy(enabled, generationEnabled, publishingEnabled, targetUserId,
                content.policy(), dailyGenerationLimit, dailyPublishLimit, Duration.ofHours(reviewTtlHours),
                Duration.ofSeconds(publishIntervalSeconds), Duration.ofSeconds(claimTimeoutSeconds));
        if (enabled && generationEnabled) policy.requireGeneration();
        if (enabled && publishingEnabled) policy.requirePublishing();
        return policy;
    }
}
