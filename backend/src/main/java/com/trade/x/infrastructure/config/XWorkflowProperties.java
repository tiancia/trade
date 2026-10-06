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
        private String direction = "面向成年英文读者，以性吸引、欲望、亲密关系与沟通为主线，辅以约会、自尊、孤独和日常关系观察；尊重同意与边界，非露骨且不以性唤起为目的";
        private String language = "English";
        private String tone = "自然地道的英文，坦诚、机智、有具体细节和关系张力，温暖而不说教，不制造性别对立";
        private String instructions = "三个候选采用不同形式，轮换短观点、微场景、两句对话、2至3项微清单、开放问题与短诗；可用换行和空行，每条是完整独立的单帖；不求赞求关注，不默认加标签、表情和链接，不写Markdown；虚构按正文语言标明，reviewNote用简体中文；仅自愿成年人且非露骨，不冒充性健康或心理治疗建议";
        private int minChars = 40;
        private int maxChars = 260;
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
