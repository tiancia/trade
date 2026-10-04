package com.trade.x.infrastructure.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.x")
public class XPublishingProperties {
    private boolean livePublishingEnabled = false;
    @ToString.Exclude private String adminToken = "";

    public String requiredAdminToken() {
        if (adminToken == null || adminToken.isBlank()) throw new IllegalArgumentException("X admin token is required");
        return adminToken;
    }
}
