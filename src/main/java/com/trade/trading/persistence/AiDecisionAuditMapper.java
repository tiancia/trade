package com.trade.trading.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiDecisionAuditMapper {
    void createSchema(@Param("schemaName") String schemaName);

    void createDecisionRunsTable(
            @Param("decisionRunsTable") String decisionRunsTable
    );

    void createAiRequestsTable(
            @Param("aiRequestsTable") String aiRequestsTable,
            @Param("decisionRunsTable") String decisionRunsTable
    );

    void createAiResponsesTable(
            @Param("aiResponsesTable") String aiResponsesTable,
            @Param("decisionRunsTable") String decisionRunsTable
    );

    void createOrderExecutionsTable(
            @Param("orderExecutionsTable") String orderExecutionsTable,
            @Param("decisionRunsTable") String decisionRunsTable
    );

    void upsertDecisionRun(AiDecisionRunRow row);

    void upsertAiRequest(AiRequestRow row);

    void upsertAiResponse(AiResponseRow row);

    void upsertOrderExecution(OrderExecutionRow row);
}
