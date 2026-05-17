package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class AiDecisionRunRow {
    private Long id;
    private Timestamp startedAt;
    private Timestamp completedAt;
    private String instId;
    private String instType;
    private String baseCcy;
    private String quoteCcy;
    private String tdMode;
    private String triggerType;
    private String triggerReason;
    private String triggerDetailsJson;
    private String action;
    private String decisionReason;
    private BigDecimal buyQuoteAmount;
    private BigDecimal sellBaseAmount;
    private BigDecimal requestedOrderSize;
    private BigDecimal winProbability;
    private BigDecimal confidence;
    private String strategyBias;
    private String strategyThesis;
    private String strategyInvalidation;
    private String strategyHorizon;
    private BigDecimal lastPrice;
    private BigDecimal availableBase;
    private BigDecimal availableQuote;
    private String executionStatus;
    private String skipReason;
    private String error;
}
