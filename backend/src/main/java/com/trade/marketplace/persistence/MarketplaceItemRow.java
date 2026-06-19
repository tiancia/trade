package com.trade.marketplace.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class MarketplaceItemRow {
    private Long id;
    private Long sellerId;
    private String sellerUsername;
    private String sellerDisplayName;
    private Long categoryId;
    private String categoryName;
    private String categorySlug;
    private String title;
    private String description;
    private String imageUrl;
    private BigDecimal price;
    private String status;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
