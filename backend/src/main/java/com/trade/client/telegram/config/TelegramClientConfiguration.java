package com.trade.client.telegram.config;

import com.trade.client.telegram.TelegramApi;
import com.trade.client.telegram.TelegramClientProperties;
import com.trade.client.telegram.TelegramHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared Bot API transport; consumers own review rules and polling lifecycle. */
@Configuration
@EnableConfigurationProperties(TelegramClientProperties.class)
public class TelegramClientConfiguration {
    @Bean
    public TelegramApi telegramApi(TelegramClientProperties properties) {
        return new TelegramApi(new TelegramHttpClient(properties));
    }
}
