package com.trade.trading.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiDecisionAuditMapper {
    void insertDecisionRun(AiDecisionRunRow row);

    void updateDecisionRun(AiDecisionRunRow row);

    void upsertAiRequest(AiRequestRow row);

    void upsertAiResponse(AiResponseRow row);

    void upsertOrderExecution(OrderExecutionRow row);
}
