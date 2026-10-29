package com.trade.trading.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiDecisionAuditMapper {
    void insertDecisionRun(AiDecisionRunRow row);

    void updateDecisionRun(AiDecisionRunRow row);

    default void upsertAiRequest(AiRequestRow row) {
        if (aiRequestExists(row.getDecisionRunId())) {
            updateAiRequest(row);
        } else {
            insertAiRequest(row);
        }
    }

    int updateAiRequest(AiRequestRow row);

    boolean aiRequestExists(Long decisionRunId);

    void insertAiRequest(AiRequestRow row);

    default void upsertAiResponse(AiResponseRow row) {
        if (aiResponseExists(row.getDecisionRunId())) {
            updateAiResponse(row);
        } else {
            insertAiResponse(row);
        }
    }

    int updateAiResponse(AiResponseRow row);

    boolean aiResponseExists(Long decisionRunId);

    void insertAiResponse(AiResponseRow row);

    default void upsertOrderExecution(OrderExecutionRow row) {
        if (orderExecutionExists(row.getDecisionRunId())) {
            updateOrderExecution(row);
        } else {
            insertOrderExecution(row);
        }
    }

    int updateOrderExecution(OrderExecutionRow row);

    boolean orderExecutionExists(Long decisionRunId);

    void insertOrderExecution(OrderExecutionRow row);
}
