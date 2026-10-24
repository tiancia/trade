package com.trade.automation.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independent application verification of operator credentials. */
@Data
@ConfigurationProperties(prefix = "trade.management.auth")
public class ManagementAuthenticationProperties {
    private boolean enabled = true;
    private String credentialsFile = "";
}
