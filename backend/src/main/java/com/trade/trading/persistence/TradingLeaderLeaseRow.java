package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class TradingLeaderLeaseRow {
    private String leaseName;
    private String ownerId;
    private Instant leaseUntil;
    private long fencingToken;
    private Instant createdAt;
    private Instant updatedAt;
}
