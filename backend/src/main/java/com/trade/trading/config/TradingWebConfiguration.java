package com.trade.trading.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** CORS boundary for the independently deployed trading cockpit. */
@Configuration
public class TradingWebConfiguration implements WebMvcConfigurer {
    private final TradingProperties properties;

    public TradingWebConfiguration(TradingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> patterns = properties.getFrontendAllowedOriginPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return;
        }
        registry.addMapping("/api/trading/**")
                .allowedOriginPatterns(patterns.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept")
                .allowCredentials(false)
                .maxAge(3600L);
    }
}
