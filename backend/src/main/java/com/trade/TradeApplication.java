package com.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot entry point for the backend.
 *
 * <p>Component scanning starts at {@code com.trade}, so new backend domains
 * should be placed under this package if they need Spring discovery.</p>
 */
@SpringBootApplication
public class TradeApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper httpObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
