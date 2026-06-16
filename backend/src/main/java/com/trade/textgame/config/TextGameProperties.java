package com.trade.textgame.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trade.text-game")
public class TextGameProperties {
    private String adminToken = "";
    private int sessionRetentionDays = 30;
    private boolean seedEnabled = true;

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken;
    }

    public int getSessionRetentionDays() {
        return sessionRetentionDays;
    }

    public void setSessionRetentionDays(int sessionRetentionDays) {
        this.sessionRetentionDays = sessionRetentionDays;
    }

    public boolean isSeedEnabled() {
        return seedEnabled;
    }

    public void setSeedEnabled(boolean seedEnabled) {
        this.seedEnabled = seedEnabled;
    }
}
