package com.trade.marketplace.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class MarketplaceSessionRow {
    private String tokenHash;
    private Long userId;
    private Timestamp expiresAt;
    private Timestamp revokedAt;
    private Timestamp lastSeenAt;
    private Timestamp createdAt;
}
