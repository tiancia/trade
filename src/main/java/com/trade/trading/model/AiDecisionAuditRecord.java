package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.UUID;

@Data
@Accessors(chain = true)
public class AiDecisionAuditRecord {
    private UUID decisionId;
    private Instant startedAt;
    private Instant completedAt;
    private TradingTrigger trigger;
    private TradingDecisionContext context;
    private String prompt;
    private String rawAiResponse;
    private AiTradingDecision aiDecision;
    private TradingDecisionRecord decisionRecord;
    private String error;
}
