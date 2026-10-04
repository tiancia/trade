package com.trade.automation.infrastructure.config;

import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(AutomationProperties.class)
public class AutomationConfiguration {

    @Bean
    public InitializingBean telegramReviewConsumerGuard(WeiboTelegramReviewProperties weibo, XTelegramReviewProperties x) {
        return () -> {
            if (weibo.isEnabled() && x.isEnabled()) {
                throw new IllegalArgumentException("Weibo and X share one Telegram bot: disable trade.weibo.review.telegram.enabled before enabling X review");
            }
        };
    }

    @Bean(name = "automationTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler automationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("automation-task-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
