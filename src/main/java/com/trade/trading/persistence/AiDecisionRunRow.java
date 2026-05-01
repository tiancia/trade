package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class AiDecisionRunRow {
    private String tableName;
    private String decisionId;
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
    private BigDecimal lastPrice;
    private BigDecimal availableBase;
    private BigDecimal availableQuote;
    private String executionStatus;
    private String skipReason;
    private String error;
}
