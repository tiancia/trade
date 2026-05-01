package com.trade.trading.persistence;

import com.trade.trading.model.AiDecisionAuditRecord;

public interface AiDecisionAuditSink {
    void save(AiDecisionAuditRecord record);
}
