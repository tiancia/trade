package com.trade.x.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.x")
public class XPublishingProperties {
    private boolean livePublishingEnabled = false;
}
