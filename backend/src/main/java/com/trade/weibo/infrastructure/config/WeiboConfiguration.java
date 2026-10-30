package com.trade.weibo.infrastructure.config;

import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.client.weibo.WeiboHttpClient;
import com.trade.weibo.domain.model.WeiboWorkflowPolicy;
import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.weibo.port.HumanReviewGateway;
import com.trade.weibo.port.WeiboReviewDeliveryStore;
import com.trade.weibo.infrastructure.review.TelegramHumanReviewGateway;
import com.trade.weibo.infrastructure.review.UnavailableHumanReviewGateway;
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
    public HumanReviewGateway telegramWeiboReviewGateway(TelegramApi api, TelegramClientProperties client,
            WeiboTelegramReviewProperties review, WeiboWorkflowProperties workflow, WeiboReviewDeliveryStore deliveries) {
        review.validate(client, workflow);
        if (!workflow.isEnabled() || !review.isEnabled()) return new UnavailableHumanReviewGateway();
        return new TelegramHumanReviewGateway(api, client, review, workflow, deliveries);
    }
}
