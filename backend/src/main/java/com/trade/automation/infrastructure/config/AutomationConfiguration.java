package com.trade.automation.infrastructure.config;

import com.trade.weibo.infrastructure.config.WeiboTelegramReviewProperties;
import com.trade.weibo.infrastructure.config.WeiboWorkflowProperties;
import com.trade.x.infrastructure.config.XTelegramReviewProperties;
import com.trade.x.infrastructure.config.XWorkflowProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(AutomationProperties.class)
public class AutomationConfiguration {

    @Bean
    public InitializingBean telegramReviewConsumerGuard(WeiboTelegramReviewProperties weibo, XTelegramReviewProperties x,
            WeiboWorkflowProperties weiboWorkflow, XWorkflowProperties xWorkflow) {
        return () -> {
            if (weiboWorkflow.isEnabled() && weibo.isEnabled() && xWorkflow.isEnabled() && x.isEnabled()) {
                throw new IllegalArgumentException("Weibo and X share one Telegram bot: disable trade.weibo.review.telegram.enabled before enabling X review");
            }
        };
    }

    @Bean(name = "automationTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler automationTaskScheduler() {
        return scheduler(4, "automation-task-");
    }

    @Bean(name = "tradingTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler tradingTaskScheduler() {
        // Trading loops share one task lock, so reserve one execution thread
        // plus one for the database lease heartbeat. Provider/AI requests in
        // other domains must never consume either resource.
        return scheduler(2, "trading-task-");
    }

    private static ThreadPoolTaskScheduler scheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
