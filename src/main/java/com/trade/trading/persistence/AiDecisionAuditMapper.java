package com.trade.trading.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiDecisionAuditMapper {
    void upsertDecisionRun(AiDecisionRunRow row);

    void upsertAiRequest(AiRequestRow row);

    void upsertAiResponse(AiResponseRow row);

    void upsertOrderExecution(OrderExecutionRow row);
}
