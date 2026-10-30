package com.trade.automation.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.automation")
public class AutomationProperties {
    private ModuleProperties trading = new ModuleProperties();
    private ModuleProperties tradingAi = new ModuleProperties();
    private ModuleProperties polymarket = new ModuleProperties();
    private ModuleProperties story = new ModuleProperties();
    private ModuleProperties weibo = new ModuleProperties();
    private ModuleProperties x = new ModuleProperties();

    @Data
    public static class ModuleProperties {
        // Unset follows the module switch; false keeps an enabled module manual-only.
        private Boolean autoStart;

        public boolean shouldAutoStart(boolean moduleEnabled) {
            return autoStart != null ? autoStart : moduleEnabled;
        }
    }
}
