package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class AiResponseRow {
    private Long decisionRunId;
    private Timestamp receivedAt;
    private String rawResponse;
    private String parsedAction;
    private String parsedReason;
    private BigDecimal parsedBuyQuoteAmount;
    private BigDecimal parsedSellBaseAmount;
    private BigDecimal parsedOrderSize;
    private BigDecimal parsedWinProbability;
    private BigDecimal parsedConfidence;
    private String parsedStrategyBias;
    private String parsedStrategyThesis;
    private String parsedStrategyInvalidation;
    private String parsedStrategyHorizon;
}
