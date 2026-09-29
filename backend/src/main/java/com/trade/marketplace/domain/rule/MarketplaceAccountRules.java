package com.trade.marketplace.domain.rule;

import java.util.regex.Pattern;

/** Account naming and credential input invariants. */
public final class MarketplaceAccountRules {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,32}");
    public static String cleanUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username must be 3-32 letters, numbers, dots, dashes, or underscores");
        }
        return username;
    }
    public static String requiredPassword(String value) {
        String password = value == null ? "" : value;
        if (password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("password must be 8-128 characters");
        }
        return password;
    }
    public static String cleanDisplayName(String value, String fallback) {
        String displayName = value == null || value.isBlank() ? fallback : value.trim();
        if (displayName.length() > 40) {
            throw new IllegalArgumentException("displayName must be at most 40 characters");
        }
        return displayName;
    }
}
