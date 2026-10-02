package com.trade.weibo.infrastructure.config;

import com.trade.weibo.domain.model.WeiboWorkflowPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "trade.weibo.workflow")
public class WeiboWorkflowProperties {
    private boolean enabled = false;
    private boolean generationEnabled = false;
    private boolean publishingEnabled = false;
    private String targetUid = "";
    private String feedUrl = "";
    private int maxBodyChars = 280;
    private int dailyGenerationLimit = 5;
    private int dailyPublishLimit = 3;
    private long eventMaxAgeHours = 24;
    private long reviewTtlHours = 6;
    private long publishIntervalSeconds = 1800;
    private long claimTimeoutSeconds = 300;
    private long generationFixedDelayMs = 900000;
    private long publishingFixedDelayMs = 60000;
    private long initialDelayMs = 30000;

    public WeiboWorkflowPolicy policy() {
        if (generationFixedDelayMs < 1000 || publishingFixedDelayMs < 1000 || initialDelayMs < 0) {
            throw new IllegalArgumentException("Invalid Weibo scheduler delays");
        }
        return new WeiboWorkflowPolicy(enabled, generationEnabled, publishingEnabled, targetUid,
                maxBodyChars, dailyGenerationLimit, dailyPublishLimit, Duration.ofHours(eventMaxAgeHours),
                Duration.ofHours(reviewTtlHours), Duration.ofSeconds(publishIntervalSeconds),
                Duration.ofSeconds(claimTimeoutSeconds));
    }
}
