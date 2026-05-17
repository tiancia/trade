package com.trade.polymarket.persistence;

import com.trade.polymarket.model.PolymarketDecisionAuditRecord;

public interface PolymarketDecisionAuditSink {
    default Long start(PolymarketDecisionAuditRecord record) {
        return null;
    }

    void save(PolymarketDecisionAuditRecord record);
}
