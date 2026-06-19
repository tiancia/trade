package com.trade.marketplace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class MarketplaceConfiguration {
    @Bean
    public PasswordEncoder marketplacePasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
