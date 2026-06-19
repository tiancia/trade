package com.trade.marketplace.application;

import com.trade.marketplace.model.MarketplaceApi;
import com.trade.marketplace.persistence.MarketplaceCategoryRow;
import com.trade.marketplace.persistence.MarketplaceConversationRow;
import com.trade.marketplace.persistence.MarketplaceItemRow;
import com.trade.marketplace.persistence.MarketplaceMessageRow;
import com.trade.marketplace.persistence.MarketplaceUserRow;

import java.sql.Timestamp;
import java.time.Instant;

final class MarketplaceViews {
    private MarketplaceViews() {
    }

    static MarketplaceApi.User user(MarketplaceUserRow row) {
        return new MarketplaceApi.User(row.getId(), row.getUsername(), row.getDisplayName());
    }

    static MarketplaceApi.User user(Long id, String username, String displayName) {
        if (id == null) {
            return null;
        }
        return new MarketplaceApi.User(id, username, displayName);
    }

    static MarketplaceApi.Category category(MarketplaceCategoryRow row) {
        return new MarketplaceApi.Category(row.getId(), row.getName(), row.getSlug());
    }

    static MarketplaceApi.Item item(MarketplaceItemRow row) {
        return new MarketplaceApi.Item(
                row.getId(),
                row.getTitle(),
                row.getDescription(),
                new MarketplaceApi.Category(row.getCategoryId(), row.getCategoryName(), row.getCategorySlug()),
                row.getImageUrl(),
                row.getPrice(),
                row.getStatus(),
                user(row.getSellerId(), row.getSellerUsername(), row.getSellerDisplayName()),
                instant(row.getCreatedAt()),
                instant(row.getUpdatedAt())
        );
    }

    static MarketplaceApi.Conversation conversation(MarketplaceConversationRow row) {
        return new MarketplaceApi.Conversation(
                row.getId(),
                conversationItem(row),
                user(row.getBuyerId(), row.getBuyerUsername(), row.getBuyerDisplayName()),
                user(row.getSellerId(), row.getSellerUsername(), row.getSellerDisplayName()),
                lastMessage(row),
                instant(row.getUpdatedAt())
        );
    }

    static MarketplaceApi.Message message(MarketplaceMessageRow row) {
        return new MarketplaceApi.Message(
                row.getId(),
                row.getConversationId(),
                user(row.getSenderId(), row.getSenderUsername(), row.getSenderDisplayName()),
                row.getBody(),
                instant(row.getCreatedAt())
        );
    }

    private static MarketplaceApi.Item conversationItem(MarketplaceConversationRow row) {
        return new MarketplaceApi.Item(
                row.getItemId(),
                row.getItemTitle(),
                row.getItemDescription(),
                new MarketplaceApi.Category(row.getItemCategoryId(), row.getItemCategoryName(), row.getItemCategorySlug()),
                row.getItemImageUrl(),
                row.getItemPrice(),
                row.getItemStatus(),
                user(row.getSellerId(), row.getSellerUsername(), row.getSellerDisplayName()),
                instant(row.getItemCreatedAt()),
                instant(row.getItemUpdatedAt())
        );
    }

    private static MarketplaceApi.Message lastMessage(MarketplaceConversationRow row) {
        if (row.getLastMessageId() == null) {
            return null;
        }
        return new MarketplaceApi.Message(
                row.getLastMessageId(),
                row.getId(),
                user(
                        row.getLastMessageSenderId(),
                        row.getLastMessageSenderUsername(),
                        row.getLastMessageSenderDisplayName()
                ),
                row.getLastMessageBody(),
                instant(row.getLastMessageCreatedAt())
        );
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
