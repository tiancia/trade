package com.trade.x.infrastructure.config;

import com.trade.client.x.XApi;
import com.trade.client.x.XClientProperties;
import com.trade.client.x.XHttpClient;
import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.x.port.XHumanReviewGateway;
import com.trade.x.port.XReviewDeliveryStore;
import com.trade.x.domain.model.XWorkflowPolicy;
import com.trade.x.infrastructure.review.TelegramXReviewGateway;
import com.trade.x.infrastructure.review.UnavailableXReviewGateway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({XClientProperties.class, XWorkflowProperties.class,
        XPublishingProperties.class, XTelegramReviewProperties.class})
public class XConfiguration {
    @Bean public XWorkflowPolicy xWorkflowPolicy(XWorkflowProperties properties) { return properties.policy(); }
    @Bean public XApi xApi(XClientProperties properties) { return new XApi(new XHttpClient(properties)); }

    @Bean
    public XHumanReviewGateway telegramXReviewGateway(TelegramApi api, TelegramClientProperties client,
            XTelegramReviewProperties review, XWorkflowProperties workflow, XReviewDeliveryStore deliveries) {
        review.validate(client, workflow);
        if (!review.isEnabled()) return new UnavailableXReviewGateway();
        return new TelegramXReviewGateway(api, client, review, workflow, deliveries);
    }
}
