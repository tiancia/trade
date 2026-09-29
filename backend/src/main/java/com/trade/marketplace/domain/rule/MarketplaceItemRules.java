package com.trade.marketplace.domain.rule;

import com.trade.marketplace.domain.exception.MarketplaceForbiddenException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;

/** Listing ownership, price, and uploaded-image invariants. */
public final class MarketplaceItemRules {
    private MarketplaceItemRules() {}

    public static boolean isSeller(Long sellerId, Long userId) {
        return userId != null && sellerId != null && sellerId.equals(userId);
    }

    public static boolean isVisible(String status, Long sellerId, Long userId) {
        return "LISTED".equals(status) || isSeller(sellerId, userId);
    }

    public static void requireSeller(Long sellerId, Long userId) {
        if (!isSeller(sellerId, userId)) {
            throw new MarketplaceForbiddenException("only the seller can delist this item");
        }
    }

    public static BigDecimal normalizePrice(BigDecimal price) {
        if (price == null) {
            return null;
        }
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price cannot be negative");
        }
        return price.scale() > 2 ? price.setScale(2, RoundingMode.HALF_UP) : price;
    }

    public static void validateImageUrl(String imageUrl, String expectedPrefix) {
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("imageUrl must be a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("imageUrl must use HTTPS");
        }
        if (expectedPrefix != null && !imageUrl.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("imageUrl must reference an uploaded marketplace image");
        }
    }
}
