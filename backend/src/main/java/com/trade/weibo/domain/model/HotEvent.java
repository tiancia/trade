package com.trade.weibo.domain.model;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Sourced event input, not a model-generated claim. */
public record HotEvent(String title, String sourceUrl, String summary, Instant occurredAt,
                       Instant collectedAt) {
    public HotEvent {
        title = required(title, 300, "title");
        sourceUrl = required(sourceUrl, 2048, "sourceUrl");
        summary = required(summary, 10000, "summary");
        URI source = URI.create(sourceUrl);
        if (!("https".equalsIgnoreCase(source.getScheme()) || "http".equalsIgnoreCase(source.getScheme()))
                || source.getHost() == null || source.getUserInfo() != null || occurredAt == null
                || collectedAt == null || occurredAt.isAfter(collectedAt.plusSeconds(300))) {
            throw new IllegalArgumentException("Invalid event source or timestamp");
        }
    }

    public String key() {
        try {
            // One event per canonical source URL, even if its headline or date is edited.
            URI source = URI.create(sourceUrl).normalize();
            String identity = source.getScheme().toLowerCase() + "://" + source.getHost().toLowerCase()
                    + (source.getPort() < 0 ? "" : ":" + source.getPort())
                    + (source.getRawPath() == null || source.getRawPath().isEmpty() ? "/" : source.getRawPath())
                    + (source.getRawQuery() == null ? "" : "?" + source.getRawQuery());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String required(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Invalid event " + name);
        }
        return value.trim();
    }
}
