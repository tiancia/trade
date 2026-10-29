package com.trade.polymarket.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PolymarketDecisionAuditMapper {
    void insertDecisionRun(PolymarketDecisionAuditRow row);

    void updateDecisionRun(PolymarketDecisionAuditRow row);

    default void upsertAiRequest(PolymarketDecisionAuditRow row) {
        if (aiRequestExists(row.getDecisionRunId())) {
            updateAiRequest(row);
        } else {
            insertAiRequest(row);
        }
    }

    int updateAiRequest(PolymarketDecisionAuditRow row);

    boolean aiRequestExists(Long decisionRunId);

    void insertAiRequest(PolymarketDecisionAuditRow row);

    default void upsertAiResponse(PolymarketDecisionAuditRow row) {
        if (aiResponseExists(row.getDecisionRunId())) {
            updateAiResponse(row);
        } else {
            insertAiResponse(row);
        }
    }

    int updateAiResponse(PolymarketDecisionAuditRow row);

    boolean aiResponseExists(Long decisionRunId);

    void insertAiResponse(PolymarketDecisionAuditRow row);

    default void upsertOrderExecution(PolymarketDecisionAuditRow row) {
        if (orderExecutionExists(row.getDecisionRunId())) {
            updateOrderExecution(row);
        } else {
            insertOrderExecution(row);
        }
    }

    int updateOrderExecution(PolymarketDecisionAuditRow row);

    boolean orderExecutionExists(Long decisionRunId);

    void insertOrderExecution(PolymarketDecisionAuditRow row);
}
