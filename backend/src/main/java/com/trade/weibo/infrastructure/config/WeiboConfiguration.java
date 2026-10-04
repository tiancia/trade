package com.trade.weibo.infrastructure.config;

import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.client.weibo.WeiboHttpClient;
import com.trade.weibo.domain.model.WeiboWorkflowPolicy;
import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.weibo.application.port.HumanReviewGateway;
import com.trade.weibo.application.port.WeiboReviewDeliveryStore;
import com.trade.weibo.infrastructure.review.TelegramHumanReviewGateway;
import com.trade.weibo.infrastructure.review.UnavailableHumanReviewGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WeiboClientProperties.class, WeiboWorkflowProperties.class,
        WeiboTelegramReviewProperties.class})
public class WeiboConfiguration {
    @Bean
    public WeiboWorkflowPolicy weiboWorkflowPolicy(WeiboWorkflowProperties properties) {
        return properties.policy();
    }
    @Bean
    public WeiboApi weiboApi(WeiboClientProperties properties) {
        return new WeiboApi(new WeiboHttpClient(properties));
    }

    @Bean
    @ConditionalOnProperty(prefix = "trade.weibo.review.telegram", name = "enabled", havingValue = "true")
    public HumanReviewGateway telegramWeiboReviewGateway(TelegramApi api, TelegramClientProperties client,
            WeiboTelegramReviewProperties review, WeiboWorkflowProperties workflow, WeiboReviewDeliveryStore deliveries) {
        review.validate(client, workflow);
        return new TelegramHumanReviewGateway(api, client, review, workflow, deliveries);
    }

    @Bean
    @ConditionalOnProperty(prefix = "trade.weibo.review.telegram", name = "enabled", havingValue = "false", matchIfMissing = true)
    public HumanReviewGateway unavailableWeiboReviewGateway(WeiboTelegramReviewProperties review,
            TelegramClientProperties client, WeiboWorkflowProperties workflow) {
        review.validate(client, workflow);
        return new UnavailableHumanReviewGateway();
    }
}
