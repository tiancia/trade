package com.trade.marketplace.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class MarketplaceConversationRow {
    private Long id;
    private Long itemId;
    private String itemTitle;
    private String itemDescription;
    private Long itemCategoryId;
    private String itemCategoryName;
    private String itemCategorySlug;
    private String itemImageUrl;
    private BigDecimal itemPrice;
    private String itemStatus;
    private Timestamp itemCreatedAt;
    private Timestamp itemUpdatedAt;
    private Long buyerId;
    private String buyerUsername;
    private String buyerDisplayName;
    private Long sellerId;
    private String sellerUsername;
    private String sellerDisplayName;
    private Long lastMessageId;
    private Long lastMessageSenderId;
    private String lastMessageSenderUsername;
    private String lastMessageSenderDisplayName;
    private String lastMessageBody;
    private Timestamp lastMessageCreatedAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
