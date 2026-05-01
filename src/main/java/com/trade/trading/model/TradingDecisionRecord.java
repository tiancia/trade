package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Accessors(chain = true)
public class TradingDecisionRecord {
    private UUID decisionId;
    private String timestamp;
    private String triggerType;
    private String triggerReason;
    private TradingAction action;
    private String reason;
    private BigDecimal buyQuoteAmountUsdt;
    private BigDecimal sellBaseAmountBtc;
    private BigDecimal lastPrice;
    private BigDecimal availableBase;
    private BigDecimal availableQuote;
    private String executionStatus;
    private String skipReason;
    private String orderId;
    private String clientOrderId;
    private String orderSize;
    private BigDecimal filledBaseAmount;
    private BigDecimal averageFillPrice;
    private BigDecimal fee;
    private String feeCcy;
    private String error;
}
