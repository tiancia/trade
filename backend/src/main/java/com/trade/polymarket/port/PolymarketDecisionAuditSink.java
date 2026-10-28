package com.trade.polymarket.port;

import com.trade.polymarket.domain.model.PolymarketDecisionAuditRecord;

public interface PolymarketDecisionAuditSink {
    default Long start(PolymarketDecisionAuditRecord record) {
        return null;
    }

    void save(PolymarketDecisionAuditRecord record);
}
