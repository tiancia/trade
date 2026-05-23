package com.trade.trading.persistence;

import com.trade.trading.model.AiDecisionAuditRecord;

public interface AiDecisionAuditSink {
    default Long start(AiDecisionAuditRecord record) {
        return null;
    }

    void save(AiDecisionAuditRecord record);
}
