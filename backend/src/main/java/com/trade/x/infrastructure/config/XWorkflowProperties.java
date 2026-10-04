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
        private String direction = "面向成年读者的原创文学短章，写日常细节、孤独、关系、欲言又止与自我和解；可有克制的成人暧昧，不靠猎奇消费苦难";
        private String language = "简体中文";
        private String tone = "有画面、有情绪张力、语言准确克制，亲近而不讨好，结尾留有余味";
        private String instructions = "轮换生活切片、微型叙事、独白与短诗；每条聚焦一个场景和一种情绪；不堆砌辞藻，不写鸡汤或求赞求关注，不默认加标签、表情和链接；虚构不冒充真实经历，成人暧昧限于自愿且非露骨的表达";
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
