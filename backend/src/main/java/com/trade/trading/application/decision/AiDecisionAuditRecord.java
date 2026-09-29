package com.trade.trading.application.decision;

import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingTrigger;
import com.trade.trading.domain.model.AiTradingDecision;
import com.trade.trading.domain.model.TradingDecisionRecord;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/** Application audit envelope; contains prompt text and raw collection context, not domain invariants. */
@Data
@Accessors(chain = true)
public class AiDecisionAuditRecord {
    private Long decisionId;
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
