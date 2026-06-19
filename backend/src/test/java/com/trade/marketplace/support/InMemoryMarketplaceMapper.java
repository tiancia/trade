package com.trade.marketplace.support;

import com.trade.marketplace.persistence.MarketplaceCategoryRow;
import com.trade.marketplace.persistence.MarketplaceConversationRow;
import com.trade.marketplace.persistence.MarketplaceItemRow;
import com.trade.marketplace.persistence.MarketplaceMapper;
import com.trade.marketplace.persistence.MarketplaceMessageRow;
import com.trade.marketplace.persistence.MarketplaceSessionRow;
import com.trade.marketplace.persistence.MarketplaceUserRow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class InMemoryMarketplaceMapper implements MarketplaceMapper {
    private final Map<Long, MarketplaceUserRow> users = new LinkedHashMap<>();
    private final Map<String, MarketplaceSessionRow> sessions = new LinkedHashMap<>();
    private final Map<Long, MarketplaceCategoryRow> categories = new LinkedHashMap<>();
    private final Map<Long, MarketplaceItemRow> items = new LinkedHashMap<>();
    private final Map<Long, MarketplaceConversationRow> conversations = new LinkedHashMap<>();
    private final Map<Long, MarketplaceMessageRow> messages = new LinkedHashMap<>();
    private long userSequence;
    private long categorySequence;
    private long itemSequence;
    private long conversationSequence;
    private long messageSequence;

    public MarketplaceCategoryRow addCategory(String name, String slug, int sortOrder) {
        MarketplaceCategoryRow row = new MarketplaceCategoryRow()
                .setId(++categorySequence)
                .setName(name)
                .setSlug(slug)
                .setSortOrder(sortOrder)
                .setCreatedAt(now());
        categories.put(row.getId(), copy(row));
        return row;
    }

    @Override
    public MarketplaceUserRow findUserByUsername(String username) {
        return users.values().stream()
                .filter(row -> row.getUsername().equals(username))
                .findFirst()
                .map(this::copy)
                .orElse(null);
    }

    @Override
    public MarketplaceUserRow findUserById(long id) {
        MarketplaceUserRow row = users.get(id);
        return row == null ? null : copy(row);
    }

    @Override
    public void insertUser(MarketplaceUserRow row) {
        if (findUserByUsername(row.getUsername()) != null) {
            throw new org.springframework.dao.DuplicateKeyException("duplicate username");
        }
        row.setId(++userSequence).setCreatedAt(now()).setUpdatedAt(now());
        users.put(row.getId(), copy(row));
    }

    @Override
    public void insertSession(MarketplaceSessionRow row) {
        row.setCreatedAt(row.getCreatedAt() == null ? now() : row.getCreatedAt());
        sessions.put(row.getTokenHash(), copy(row));
    }

    @Override
    public MarketplaceSessionRow findSessionByTokenHash(String tokenHash) {
        MarketplaceSessionRow row = sessions.get(tokenHash);
        return row == null ? null : copy(row);
    }

    @Override
    public int revokeSession(String tokenHash, Timestamp revokedAt) {
        MarketplaceSessionRow row = sessions.get(tokenHash);
        if (row == null || row.getRevokedAt() != null) {
            return 0;
        }
        row.setRevokedAt(revokedAt);
        return 1;
    }

    @Override
    public int touchSession(String tokenHash, Timestamp lastSeenAt) {
        MarketplaceSessionRow row = sessions.get(tokenHash);
        if (row == null) {
            return 0;
        }
        row.setLastSeenAt(lastSeenAt);
        return 1;
    }

    @Override
    public List<MarketplaceCategoryRow> listCategories() {
        return categories.values().stream()
                .sorted(Comparator.comparing(MarketplaceCategoryRow::getSortOrder).thenComparing(MarketplaceCategoryRow::getId))
                .map(this::copy)
                .toList();
    }

    @Override
    public MarketplaceCategoryRow findCategoryById(long id) {
        MarketplaceCategoryRow row = categories.get(id);
        return row == null ? null : copy(row);
    }

    @Override
    public List<MarketplaceItemRow> listItems(Long categoryId, String q, Long sellerId, boolean mine) {
        return items.values().stream()
                .filter(row -> mine ? Objects.equals(row.getSellerId(), sellerId) : "LISTED".equals(row.getStatus()))
                .filter(row -> categoryId == null || Objects.equals(row.getCategoryId(), categoryId))
                .filter(row -> q == null || contains(row.getTitle(), q) || contains(row.getDescription(), q))
                .sorted(Comparator.comparing(MarketplaceItemRow::getCreatedAt).thenComparing(MarketplaceItemRow::getId).reversed())
                .map(this::enrich)
                .toList();
    }

    @Override
    public MarketplaceItemRow findItemById(long id) {
        MarketplaceItemRow row = items.get(id);
        return row == null ? null : enrich(row);
    }

    @Override
    public void insertItem(MarketplaceItemRow row) {
        row.setId(++itemSequence).setCreatedAt(now()).setUpdatedAt(now());
        items.put(row.getId(), copy(row));
    }

    @Override
    public int delistItem(long id, long sellerId) {
        MarketplaceItemRow row = items.get(id);
        if (row == null || !Objects.equals(row.getSellerId(), sellerId) || !"LISTED".equals(row.getStatus())) {
            return 0;
        }
        row.setStatus("DELISTED").setUpdatedAt(now());
        return 1;
    }

    @Override
    public MarketplaceConversationRow findConversationForItemBuyer(long itemId, long buyerId) {
        return conversations.values().stream()
                .filter(row -> Objects.equals(row.getItemId(), itemId) && Objects.equals(row.getBuyerId(), buyerId))
                .findFirst()
                .map(this::enrich)
                .orElse(null);
    }

    @Override
    public MarketplaceConversationRow findConversationById(long id) {
        MarketplaceConversationRow row = conversations.get(id);
        return row == null ? null : enrich(row);
    }

    @Override
    public void insertConversation(MarketplaceConversationRow row) {
        if (findConversationForItemBuyer(row.getItemId(), row.getBuyerId()) != null) {
            throw new org.springframework.dao.DuplicateKeyException("duplicate conversation");
        }
        row.setId(++conversationSequence).setCreatedAt(now()).setUpdatedAt(now());
        conversations.put(row.getId(), copy(row));
    }

    @Override
    public List<MarketplaceConversationRow> listConversations(long userId) {
        return conversations.values().stream()
                .filter(row -> Objects.equals(row.getBuyerId(), userId) || Objects.equals(row.getSellerId(), userId))
                .sorted(Comparator.comparing(MarketplaceConversationRow::getUpdatedAt).thenComparing(MarketplaceConversationRow::getId).reversed())
                .map(this::enrich)
                .toList();
    }

    @Override
    public void insertMessage(MarketplaceMessageRow row) {
        row.setId(++messageSequence);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(now());
        }
        messages.put(row.getId(), copy(row));
    }

    @Override
    public void touchConversation(long id, Timestamp updatedAt) {
        MarketplaceConversationRow row = conversations.get(id);
        if (row != null) {
            row.setUpdatedAt(updatedAt);
        }
    }

    @Override
    public List<MarketplaceMessageRow> listMessages(long conversationId, Long afterId, int limit) {
        return messages.values().stream()
                .filter(row -> Objects.equals(row.getConversationId(), conversationId))
                .filter(row -> afterId == null || row.getId() > afterId)
                .sorted(Comparator.comparing(MarketplaceMessageRow::getId))
                .limit(limit)
                .map(this::enrich)
                .toList();
    }

    private MarketplaceItemRow enrich(MarketplaceItemRow row) {
        MarketplaceItemRow copy = copy(row);
        MarketplaceUserRow seller = users.get(copy.getSellerId());
        MarketplaceCategoryRow category = categories.get(copy.getCategoryId());
        return copy.setSellerUsername(seller.getUsername())
                .setSellerDisplayName(seller.getDisplayName())
                .setCategoryName(category.getName())
                .setCategorySlug(category.getSlug());
    }

    private MarketplaceConversationRow enrich(MarketplaceConversationRow row) {
        MarketplaceConversationRow copy = copy(row);
        MarketplaceItemRow item = enrich(items.get(row.getItemId()));
        MarketplaceUserRow buyer = users.get(row.getBuyerId());
        MarketplaceUserRow seller = users.get(row.getSellerId());
        copy.setItemTitle(item.getTitle())
                .setItemDescription(item.getDescription())
                .setItemCategoryId(item.getCategoryId())
                .setItemCategoryName(item.getCategoryName())
                .setItemCategorySlug(item.getCategorySlug())
                .setItemImageUrl(item.getImageUrl())
                .setItemPrice(item.getPrice())
                .setItemStatus(item.getStatus())
                .setItemCreatedAt(item.getCreatedAt())
                .setItemUpdatedAt(item.getUpdatedAt())
                .setBuyerUsername(buyer.getUsername())
                .setBuyerDisplayName(buyer.getDisplayName())
                .setSellerUsername(seller.getUsername())
                .setSellerDisplayName(seller.getDisplayName());
        messages.values().stream()
                .filter(message -> Objects.equals(message.getConversationId(), row.getId()))
                .max(Comparator.comparing(MarketplaceMessageRow::getId))
                .ifPresent(message -> {
                    MarketplaceUserRow sender = users.get(message.getSenderId());
                    copy.setLastMessageId(message.getId())
                            .setLastMessageSenderId(message.getSenderId())
                            .setLastMessageSenderUsername(sender.getUsername())
                            .setLastMessageSenderDisplayName(sender.getDisplayName())
                            .setLastMessageBody(message.getBody())
                            .setLastMessageCreatedAt(message.getCreatedAt());
                });
        return copy;
    }

    private MarketplaceMessageRow enrich(MarketplaceMessageRow row) {
        MarketplaceMessageRow copy = copy(row);
        MarketplaceUserRow sender = users.get(row.getSenderId());
        return copy.setSenderUsername(sender.getUsername()).setSenderDisplayName(sender.getDisplayName());
    }

    private static boolean contains(String value, String likePattern) {
        String needle = likePattern.replace("%", "").toLowerCase();
        return value != null && value.toLowerCase().contains(needle);
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private MarketplaceUserRow copy(MarketplaceUserRow row) {
        return new MarketplaceUserRow()
                .setId(row.getId())
                .setUsername(row.getUsername())
                .setPasswordHash(row.getPasswordHash())
                .setDisplayName(row.getDisplayName())
                .setCreatedAt(row.getCreatedAt())
                .setUpdatedAt(row.getUpdatedAt());
    }

    private MarketplaceSessionRow copy(MarketplaceSessionRow row) {
        return new MarketplaceSessionRow()
                .setTokenHash(row.getTokenHash())
                .setUserId(row.getUserId())
                .setExpiresAt(row.getExpiresAt())
                .setRevokedAt(row.getRevokedAt())
                .setLastSeenAt(row.getLastSeenAt())
                .setCreatedAt(row.getCreatedAt());
    }

    private MarketplaceCategoryRow copy(MarketplaceCategoryRow row) {
        return new MarketplaceCategoryRow()
                .setId(row.getId())
                .setName(row.getName())
                .setSlug(row.getSlug())
                .setSortOrder(row.getSortOrder())
                .setCreatedAt(row.getCreatedAt());
    }

    private MarketplaceItemRow copy(MarketplaceItemRow row) {
        return new MarketplaceItemRow()
                .setId(row.getId())
                .setSellerId(row.getSellerId())
                .setSellerUsername(row.getSellerUsername())
                .setSellerDisplayName(row.getSellerDisplayName())
                .setCategoryId(row.getCategoryId())
                .setCategoryName(row.getCategoryName())
                .setCategorySlug(row.getCategorySlug())
                .setTitle(row.getTitle())
                .setDescription(row.getDescription())
                .setImageUrl(row.getImageUrl())
                .setPrice(row.getPrice())
                .setStatus(row.getStatus())
                .setCreatedAt(row.getCreatedAt())
                .setUpdatedAt(row.getUpdatedAt());
    }

    private MarketplaceConversationRow copy(MarketplaceConversationRow row) {
        return new MarketplaceConversationRow()
                .setId(row.getId())
                .setItemId(row.getItemId())
                .setItemTitle(row.getItemTitle())
                .setItemDescription(row.getItemDescription())
                .setItemCategoryId(row.getItemCategoryId())
                .setItemCategoryName(row.getItemCategoryName())
                .setItemCategorySlug(row.getItemCategorySlug())
                .setItemImageUrl(row.getItemImageUrl())
                .setItemPrice(row.getItemPrice())
                .setItemStatus(row.getItemStatus())
                .setItemCreatedAt(row.getItemCreatedAt())
                .setItemUpdatedAt(row.getItemUpdatedAt())
                .setBuyerId(row.getBuyerId())
                .setBuyerUsername(row.getBuyerUsername())
                .setBuyerDisplayName(row.getBuyerDisplayName())
                .setSellerId(row.getSellerId())
                .setSellerUsername(row.getSellerUsername())
                .setSellerDisplayName(row.getSellerDisplayName())
                .setLastMessageId(row.getLastMessageId())
                .setLastMessageSenderId(row.getLastMessageSenderId())
                .setLastMessageSenderUsername(row.getLastMessageSenderUsername())
                .setLastMessageSenderDisplayName(row.getLastMessageSenderDisplayName())
                .setLastMessageBody(row.getLastMessageBody())
                .setLastMessageCreatedAt(row.getLastMessageCreatedAt())
                .setCreatedAt(row.getCreatedAt())
                .setUpdatedAt(row.getUpdatedAt());
    }

    private MarketplaceMessageRow copy(MarketplaceMessageRow row) {
        return new MarketplaceMessageRow()
                .setId(row.getId())
                .setConversationId(row.getConversationId())
                .setSenderId(row.getSenderId())
                .setSenderUsername(row.getSenderUsername())
                .setSenderDisplayName(row.getSenderDisplayName())
                .setBody(row.getBody())
                .setCreatedAt(row.getCreatedAt());
    }
}
