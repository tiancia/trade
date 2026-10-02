package com.trade.weibo.infrastructure.config;

import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.client.weibo.WeiboHttpClient;
import com.trade.weibo.domain.model.WeiboWorkflowPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WeiboClientProperties.class, WeiboWorkflowProperties.class})
public class WeiboConfiguration {
    @Bean
    public WeiboWorkflowPolicy weiboWorkflowPolicy(WeiboWorkflowProperties properties) {
        return properties.policy();
    }
    @Bean
    public WeiboApi weiboApi(WeiboClientProperties properties) {
        return new WeiboApi(new WeiboHttpClient(properties));
    }
}
