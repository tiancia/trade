package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class OrderExecutionRow {
    private String decisionId;
    private String instId;
    private String side;
    private String tdMode;
    private String orderType;
    private String targetCurrency;
    private BigDecimal orderSize;
    private String orderId;
    private String clientOrderId;
    private String executionStatus;
    private String skipReason;
    private BigDecimal filledBaseAmount;
    private BigDecimal averageFillPrice;
    private BigDecimal fee;
    private String feeCcy;
    private String error;
}
