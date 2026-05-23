package com.trade.polymarket.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PolymarketDecisionAuditMapper {
    void insertDecisionRun(PolymarketDecisionAuditRow row);

    void updateDecisionRun(PolymarketDecisionAuditRow row);

    void upsertAiRequest(PolymarketDecisionAuditRow row);

    void upsertAiResponse(PolymarketDecisionAuditRow row);

    void upsertOrderExecution(PolymarketDecisionAuditRow row);
}
