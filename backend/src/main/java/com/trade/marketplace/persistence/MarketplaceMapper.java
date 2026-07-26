package com.trade.marketplace.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

@Mapper
public interface MarketplaceMapper {
    MarketplaceUserRow findUserByUsername(@Param("username") String username);

    MarketplaceUserRow findUserById(@Param("id") long id);

    void insertUser(MarketplaceUserRow row);

    void insertSession(MarketplaceSessionRow row);

    MarketplaceSessionRow findSessionByTokenHash(@Param("tokenHash") String tokenHash);

    int revokeSession(@Param("tokenHash") String tokenHash, @Param("revokedAt") Timestamp revokedAt);

    int touchSession(@Param("tokenHash") String tokenHash, @Param("lastSeenAt") Timestamp lastSeenAt);

    List<MarketplaceCategoryRow> listCategories();

    MarketplaceCategoryRow findCategoryById(@Param("id") long id);

    List<MarketplaceItemRow> listItems(
            @Param("categoryId") Long categoryId,
            @Param("q") String q,
            @Param("sellerId") Long sellerId,
            @Param("mine") boolean mine,
            @Param("limit") int limit
    );

    MarketplaceItemRow findItemById(@Param("id") long id);

    void insertItem(MarketplaceItemRow row);

    int delistItem(@Param("id") long id, @Param("sellerId") long sellerId);

    MarketplaceConversationRow findConversationForItemBuyer(
            @Param("itemId") long itemId,
            @Param("buyerId") long buyerId
    );

    MarketplaceConversationRow findConversationById(@Param("id") long id);

    void insertConversation(MarketplaceConversationRow row);

    List<MarketplaceConversationRow> listConversations(@Param("userId") long userId);

    void insertMessage(MarketplaceMessageRow row);

    void touchConversation(@Param("id") long id, @Param("updatedAt") Timestamp updatedAt);

    List<MarketplaceMessageRow> listMessages(
            @Param("conversationId") long conversationId,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );
}
