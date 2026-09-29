package com.trade.trading.application.port;

import com.trade.trading.application.decision.AiDecisionAuditRecord;

public interface AiDecisionAuditSink {
    default Long start(AiDecisionAuditRecord record) {
        return null;
    }

    void save(AiDecisionAuditRecord record);
}
