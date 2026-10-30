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
    private boolean generationEnabled = true;
    private boolean publishingEnabled = true;
    private String targetUserId = "";
    private Content content = new Content();
    private int dailyGenerationLimit = 8;
    private int dailyPublishLimit = 8;
    private long reviewTtlHours = 24;
    private long publishIntervalSeconds = 1800;
    private long claimTimeoutSeconds = 300;
    private long generationFixedDelayMs = 10800000;
    private long publishingFixedDelayMs = 60000;
    private long initialDelayMs = 30000;

    @Data
    public static class Content {
        private String direction = "面向中文成年读者，创作聊天记录样式的原创虚构短对话，写成年人的暧昧、想念、试探、靠近与退让；让情绪从几句来回和潜台词里自然出现";
        private String language = "简体中文";
        private String tone = "自然简短的中文聊天口吻，有回应、有停顿、有潜台词，可温柔、嘴硬、幽默或失落；像两个人在说话，不像作者在写金句";
        private String instructions = "每条正文仅2至6句对话，一句一行，每行用成对双引号包住；不加空行、角色名、标题、旁白、解释或总结；三个候选更换关系处境、情绪与潜台词，不轮换成散文；话要接得上，不句句金句，不提供沟通话术；reviewNote用简体中文说明原创虚构、潜台词、情绪变化与核验项，绝不混入正文；不冒充真实聊天记录、截图、亲历或投稿；仅自愿成年人且非露骨，不承诺挽回、涨粉或收益";
        private int minChars = 8;
        private int maxChars = 120;
        private XContentPolicy.Format format = XContentPolicy.Format.CHAT_DIALOGUE;
        public XContentPolicy policy() {
            if (format == null) throw new IllegalArgumentException("X content format is required");
            return new XContentPolicy(direction, language, tone, instructions, minChars, maxChars, format);
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
