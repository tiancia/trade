package com.trade.marketplace.domain.rule;

import com.trade.marketplace.domain.exception.MarketplaceConflictException;
import com.trade.marketplace.domain.exception.MarketplaceForbiddenException;

/** Buyer/seller participation and message-content rules, independent of persistence rows. */
public final class MarketplaceConversationRules {
    private MarketplaceConversationRules() {}

    public static void requireCanStart(String itemStatus, Long sellerId, long buyerId) {
        if (!"LISTED".equals(itemStatus)) {
            throw new MarketplaceConflictException("item is no longer listed");
        }
        if (sellerId.equals(buyerId)) {
            throw new IllegalArgumentException("seller cannot start a conversation with themselves");
        }
    }

    public static void requireParticipant(Long buyerId, Long sellerId, long userId) {
        if (!buyerId.equals(userId) && !sellerId.equals(userId)) {
            throw new MarketplaceForbiddenException("only the buyer and seller can access this conversation");
        }
    }

    public static String cleanBody(String value) {
        String body = value == null ? "" : value.trim();
        if (body.isEmpty() || body.length() > 1000) {
            throw new IllegalArgumentException("message body must be 1-1000 characters");
        }
        return body;
    }
}
