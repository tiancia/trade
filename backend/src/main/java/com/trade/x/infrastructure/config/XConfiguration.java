package com.trade.x.infrastructure.config;

import com.trade.client.x.XApi;
import com.trade.client.x.XClientProperties;
import com.trade.client.x.XHttpClient;
import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.x.application.port.XHumanReviewGateway;
import com.trade.x.application.port.XReviewDeliveryStore;
import com.trade.x.application.port.XPostRepository;
import com.trade.x.application.port.XPostPublisher;
import com.trade.x.application.port.XDraftGenerator;
import com.trade.x.application.service.XPostService;
import com.trade.x.domain.model.XWorkflowPolicy;
import com.trade.x.infrastructure.review.TelegramXReviewGateway;
import com.trade.x.infrastructure.review.UnavailableXReviewGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties({XClientProperties.class, XWorkflowProperties.class,
        XPublishingProperties.class, XTelegramReviewProperties.class})
public class XConfiguration {
    @Bean public XWorkflowPolicy xWorkflowPolicy(XWorkflowProperties properties) { return properties.policy(); }
    @Bean public XApi xApi(XClientProperties properties) { return new XApi(new XHttpClient(properties)); }

    @Bean
    public XPostService xPostService(XPostRepository posts, XDraftGenerator generator, XHumanReviewGateway reviews,
            XPostPublisher publisher, XWorkflowPolicy policy, XWorkflowProperties settings) {
        return new XPostService(posts, generator, reviews, publisher, policy,
                settings.getGenerationFixedDelayMs(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "trade.x.review.telegram", name = "enabled", havingValue = "true")
    public XHumanReviewGateway telegramXReviewGateway(TelegramApi api, TelegramClientProperties client,
            XTelegramReviewProperties review, XWorkflowProperties workflow, XReviewDeliveryStore deliveries) {
        review.validate(client, workflow);
        return new TelegramXReviewGateway(api, client, review, workflow, deliveries);
    }

    @Bean
    @ConditionalOnProperty(prefix = "trade.x.review.telegram", name = "enabled", havingValue = "false", matchIfMissing = true)
    public XHumanReviewGateway unavailableXReviewGateway(XTelegramReviewProperties review,
            TelegramClientProperties client, XWorkflowProperties workflow) {
        review.validate(client, workflow);
        return new UnavailableXReviewGateway();
    }
}
