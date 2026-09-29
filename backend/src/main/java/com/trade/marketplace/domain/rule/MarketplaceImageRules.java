package com.trade.marketplace.domain.rule;

import java.util.List;
import java.util.Locale;

/** Allowed image media, size, and filename rules. */
public final class MarketplaceImageRules {
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );

    public static String cleanContentType(String value) {
        String contentType = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("only jpeg, png, webp, and gif images can be uploaded");
        }
        return contentType;
    }
    public static void validateSize(Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0 || sizeBytes > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("image size must be between 1 byte and 10 MB");
        }
    }
    public static String extension(String fileName, String contentType) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        for (String ext : List.of(".jpg", ".jpeg", ".png", ".webp", ".gif")) {
            if (lower.endsWith(ext)) {
                return ".jpeg".equals(ext) ? ".jpg" : ext;
            }
        }
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
