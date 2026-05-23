package com.trade.automation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.automation")
public class AutomationProperties {
    private ModuleProperties trading = new ModuleProperties();
    private ModuleProperties polymarket = new ModuleProperties();
    private ModuleProperties story = new ModuleProperties();

    @Data
    public static class ModuleProperties {
        private boolean autoStart = false;
    }
}
