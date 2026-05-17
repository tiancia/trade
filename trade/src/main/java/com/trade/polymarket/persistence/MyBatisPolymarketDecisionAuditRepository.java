package com.trade.polymarket.persistence;

import com.trade.polymarket.model.AiPolymarketDecision;
import com.trade.polymarket.model.PolymarketDecisionAuditRecord;
import com.trade.polymarket.model.PolymarketDecisionContext;
import com.trade.polymarket.model.PolymarketMarketSnapshot;
import com.trade.polymarket.model.PolymarketOrderResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class MyBatisPolymarketDecisionAuditRepository implements PolymarketDecisionAuditSink {
    private final PolymarketDecisionAuditMapper mapper;

    public MyBatisPolymarketDecisionAuditRepository(PolymarketDecisionAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Long start(PolymarketDecisionAuditRecord record) {
        if (record == null) {
            return null;
        }

        try {
            PolymarketDecisionAuditRow row = toRow(record);
            mapper.insertDecisionRun(row);
            record.setDecisionId(row.getId());
            return row.getId();
        } catch (Exception e) {
            throw new IllegalStateException("Start Polymarket decision audit record failed", e);
        }
    }

    @Override
    @Transactional
    public void save(PolymarketDecisionAuditRecord record) {
        if (record == null) {
            return;
        }

        try {
            PolymarketDecisionAuditRow row = toRow(record);
            Long decisionRunId = saveDecisionRun(row, record);
            row.setDecisionRunId(decisionRunId);
            upsertAiRequest(row);
            upsertAiResponse(row);
            upsertOrderExecution(row);
        } catch (Exception e) {
            throw new IllegalStateException("Persist Polymarket decision audit record failed", e);
        }
    }

    private Long saveDecisionRun(PolymarketDecisionAuditRow row, PolymarketDecisionAuditRecord record) {
        if (row.getId() == null) {
            mapper.insertDecisionRun(row);
        } else {
            mapper.updateDecisionRun(row);
        }
        record.setDecisionId(row.getId());
        return row.getId();
    }

    private void upsertAiRequest(PolymarketDecisionAuditRow row) {
        if (isBlank(row.getPromptText()) && isBlank(row.getAiParametersJson())) {
            return;
        }
        mapper.upsertAiRequest(row);
    }

    private void upsertAiResponse(PolymarketDecisionAuditRow row) {
        if (isBlank(row.getRawResponse()) && isBlank(row.getAction())) {
            return;
        }
        mapper.upsertAiResponse(row);
    }

    private void upsertOrderExecution(PolymarketDecisionAuditRow row) {
        if (isBlank(row.getExecutionStatus()) && isBlank(row.getSkipReason()) && isBlank(row.getOrderResponse())
                && isBlank(row.getError())) {
            return;
        }
        mapper.upsertOrderExecution(row);
    }

    private static PolymarketDecisionAuditRow toRow(PolymarketDecisionAuditRecord audit) {
        PolymarketDecisionContext context = audit.getContext();
        AiPolymarketDecision decision = audit.getAiDecision();
        PolymarketOrderResult result = audit.getOrderResult();

        return new PolymarketDecisionAuditRow()
                .setId(audit.getDecisionId())
                .setStartedAt(timestamp(audit.getStartedAt()))
                .setCompletedAt(timestamp(audit.getCompletedAt()))
                .setExecutionEnabled(audit.isExecutionEnabled())
                .setMarketCount(marketCount(context))
                .setOutcomeCount(outcomeCount(context))
                .setAiParametersJson(context == null ? null : context.getAiParametersJson())
                .setPromptText(audit.getPrompt())
                .setRawResponse(audit.getRawAiResponse())
                .setAction(decision == null || decision.getAction() == null ? null : decision.getAction().name())
                .setDecisionReason(decision == null ? null : decision.getReason())
                .setMarketId(decision == null ? null : decision.getMarketId())
                .setMarketSlug(decision == null ? null : decision.getMarketSlug())
                .setMarketQuestion(decision == null ? null : decision.getMarketQuestion())
                .setOutcome(decision == null ? null : decision.getOutcome())
                .setTokenId(decision == null ? null : decision.getTokenId())
                .setLimitPrice(decision == null ? null : decision.getLimitPrice())
                .setMaxSpendUsdc(decision == null ? null : decision.getMaxSpendUsdc())
                .setWinProbability(decision == null ? null : decision.getWinProbability())
                .setConfidence(decision == null ? null : decision.getConfidence())
                .setEstimatedProbability(decision == null ? null : decision.getEstimatedProbability())
                .setEstimatedEdge(decision == null ? null : decision.getEstimatedEdge())
                .setExecutionStatus(executionStatus(result, audit))
                .setSkipReason(result == null ? null : result.getSkipReason())
                .setOrderResponse(result == null ? null : result.getResponseBody())
                .setError(audit.getError());
    }

    private static String executionStatus(PolymarketOrderResult result, PolymarketDecisionAuditRecord audit) {
        if (result != null) {
            return result.getStatus();
        }
        return audit.getError() == null ? null : "FAILED";
    }

    private static Integer marketCount(PolymarketDecisionContext context) {
        return context == null || context.getMarkets() == null ? null : context.getMarkets().size();
    }

    private static Integer outcomeCount(PolymarketDecisionContext context) {
        if (context == null || context.getMarkets() == null) {
            return null;
        }
        return context.getMarkets().stream()
                .mapToInt(MyBatisPolymarketDecisionAuditRepository::outcomeCount)
                .sum();
    }

    private static int outcomeCount(PolymarketMarketSnapshot market) {
        return market.getOutcomes() == null ? 0 : market.getOutcomes().size();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
