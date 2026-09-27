package com.trade.polymarket.domain.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class PolymarketDecisionAuditRecord {
    private Long decisionId;
    private Instant startedAt;
    private Instant completedAt;
    private boolean executionEnabled;
    private PolymarketDecisionContext context;
    private String prompt;
    private String rawAiResponse;
    private AiPolymarketDecision aiDecision;
    private PolymarketOrderResult orderResult;
    private String error;
}
