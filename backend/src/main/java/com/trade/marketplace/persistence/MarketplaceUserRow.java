package com.trade.marketplace.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class MarketplaceUserRow {
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
