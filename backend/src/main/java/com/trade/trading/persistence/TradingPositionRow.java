package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class TradingPositionRow {
    private String accountScope;
    private String instId;
    private String positionSide;
    private BigDecimal quantity;
    private BigDecimal averageCost;
    private BigDecimal exchangeQuantity;
    private Instant lastReconciledAt;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;
}
