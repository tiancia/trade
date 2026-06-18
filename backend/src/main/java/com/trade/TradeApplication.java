package com.trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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

}
