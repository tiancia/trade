package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class OrderFillLedgerRow {
    private Long orderId;
    private String side;
    private BigDecimal cumulativeFilledSize;
    private BigDecimal appliedPositionQuantity;
    private BigDecimal appliedQuoteCost;
    private BigDecimal averageFillPrice;
    private BigDecimal fee;
    private String feeCcy;
    private String exchangeState;
    private Instant exchangeUpdatedAt;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;
}
