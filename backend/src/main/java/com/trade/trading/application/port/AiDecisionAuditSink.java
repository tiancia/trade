package com.trade.trading.application.port;

import com.trade.trading.domain.model.AiDecisionAuditRecord;

public interface AiDecisionAuditSink {
    default Long start(AiDecisionAuditRecord record) {
        return null;
    }

    void save(AiDecisionAuditRecord record);
}
