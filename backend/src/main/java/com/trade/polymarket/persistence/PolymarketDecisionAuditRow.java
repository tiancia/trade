package com.trade.polymarket.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class PolymarketDecisionAuditRow {
    private Long id;
    private Long decisionRunId;
    private Timestamp startedAt;
    private Timestamp completedAt;
    private Boolean executionEnabled;
    private Integer marketCount;
    private Integer outcomeCount;
    private String aiParametersJson;
    private String promptText;
    private String rawResponse;
    private String action;
    private String decisionReason;
    private String marketId;
    private String marketSlug;
    private String marketQuestion;
    private String outcome;
    private String tokenId;
    private BigDecimal limitPrice;
    private BigDecimal maxSpendUsdc;
    private BigDecimal winProbability;
    private BigDecimal confidence;
    private BigDecimal estimatedProbability;
    private BigDecimal estimatedEdge;
    private String executionStatus;
    private String skipReason;
    private String orderResponse;
    private String error;
}
