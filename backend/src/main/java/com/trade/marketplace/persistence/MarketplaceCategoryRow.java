package com.trade.marketplace.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class MarketplaceCategoryRow {
    private Long id;
    private String name;
    private String slug;
    private Integer sortOrder;
    private Timestamp createdAt;
}
