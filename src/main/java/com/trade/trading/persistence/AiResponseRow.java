package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class AiResponseRow {
    private String decisionId;
    private Timestamp receivedAt;
    private String rawResponse;
    private String parsedAction;
    private String parsedReason;
    private BigDecimal parsedBuyQuoteAmount;
    private BigDecimal parsedSellBaseAmount;
}
