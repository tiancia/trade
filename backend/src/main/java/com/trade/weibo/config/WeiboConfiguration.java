package com.trade.weibo.config;

import com.trade.client.weibo.WeiboApi;
import com.trade.client.weibo.WeiboClientProperties;
import com.trade.client.weibo.WeiboHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WeiboClientProperties.class)
public class WeiboConfiguration {
    @Bean
    public WeiboApi weiboApi(WeiboClientProperties properties) {
        return new WeiboApi(new WeiboHttpClient(properties));
    }
}
