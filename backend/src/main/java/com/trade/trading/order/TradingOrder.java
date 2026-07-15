package com.trade.trading.order;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

/** Operational order aggregate. This table is authoritative for submission state. */
@Data
@Accessors(chain = true)
public class TradingOrder {
    private Long id;
    private String idempotencyKey;
    private String clientOrderId;
    private String exchangeOrderId;
    private String decisionId;
    private String strategyId;
    private String instId;
    private String action;
    private String side;
    private String tdMode;
    private String orderType;
    private String targetCurrency;
    private BigDecimal requestedSize;
    private OrderStatus status;
    private long version;
    private BigDecimal filledBaseAmount;
    private BigDecimal averageFillPrice;
    private BigDecimal fee;
    private String feeCcy;
    private String failureCode;
    private String failureMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant submittedAt;
    private Instant completedAt;
}
