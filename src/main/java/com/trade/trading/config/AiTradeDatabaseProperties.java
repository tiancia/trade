package com.trade.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.ai.database")
public class AiTradeDatabaseProperties {
    private boolean enabled = true;
    private String jdbcUrl;
    private String username;
    private String password;
    private String driverClassName = "com.mysql.cj.jdbc.Driver";
    private String schema = "ai_trade";
    private boolean initializeSchema = true;
}
