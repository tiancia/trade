package com.trade.trading.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.AiDecisionAuditRecord;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.support.TradingMath;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Component
public class MyBatisAiDecisionAuditRepository implements AiDecisionAuditSink {
    private final AiDecisionAuditMapper mapper;
    private final TradingProperties tradingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public MyBatisAiDecisionAuditRepository(
            AiDecisionAuditMapper mapper,
            TradingProperties tradingProperties
    ) {
        this.mapper = mapper;
        this.tradingProperties = tradingProperties;
    }

    @Override
    @Transactional
    public Long start(AiDecisionAuditRecord record) {
        if (record == null) {
            return null;
        }

        try {
            AiDecisionRunRow row = decisionRunRow(record);
            mapper.insertDecisionRun(row);
            record.setDecisionId(row.getId());
            return row.getId();
        } catch (Exception e) {
            throw new IllegalStateException("Start AI decision audit record failed", e);
        }
    }

    @Override
    @Transactional
    public void save(AiDecisionAuditRecord record) {
        if (record == null) {
            return;
        }

        try {
            Long decisionRunId = saveDecisionRun(mapper, record);
            upsertAiRequest(mapper, record, decisionRunId);
            upsertAiResponse(mapper, record, decisionRunId);
            upsertOrderExecution(mapper, record, decisionRunId);
        } catch (Exception e) {
            throw new IllegalStateException("Persist AI decision audit record failed", e);
        }
    }

    private Long saveDecisionRun(AiDecisionAuditMapper mapper, AiDecisionAuditRecord audit) {
        AiDecisionRunRow row = decisionRunRow(audit);
        if (row.getId() == null) {
            mapper.insertDecisionRun(row);
        } else {
            mapper.updateDecisionRun(row);
        }
        audit.setDecisionId(row.getId());
        return row.getId();
    }

    private AiDecisionRunRow decisionRunRow(AiDecisionAuditRecord audit) {
        TradingDecisionRecord record = audit.getDecisionRecord();
        AiTradingDecision decision = audit.getAiDecision();
        TradingTrigger trigger = audit.getTrigger();

        return new AiDecisionRunRow()
                .setId(audit.getDecisionId())
                .setStartedAt(timestamp(audit.getStartedAt()))
                .setCompletedAt(timestamp(audit.getCompletedAt()))
                .setInstId(tradingProperties.getInstId())
                .setInstType(tradingProperties.getInstType())
                .setBaseCcy(tradingProperties.getBaseCcy())
                .setQuoteCcy(tradingProperties.getQuoteCcy())
                .setTdMode(tradingProperties.getTdMode())
                .setTriggerType(trigger == null ? null : trigger.type())
                .setTriggerReason(trigger == null ? null : trigger.reason())
                .setTriggerDetailsJson(toJson(trigger == null ? null : trigger.details()))
                .setAction(actionName(record, decision))
                .setDecisionReason(decisionReason(record, decision))
                .setBuyQuoteAmount(buyQuoteAmount(record, decision))
                .setSellBaseAmount(sellBaseAmount(record, decision))
                .setRequestedOrderSize(requestedOrderSize(record, decision))
                .setWinProbability(winProbability(record, decision))
                .setConfidence(confidence(record, decision))
                .setStrategyBias(strategyBias(record, decision))
                .setStrategyThesis(strategyThesis(record, decision))
                .setStrategyInvalidation(strategyInvalidation(record, decision))
                .setStrategyHorizon(strategyHorizon(record, decision))
                .setLastPrice(record == null ? lastPrice(audit.getContext()) : record.getLastPrice())
                .setAvailableBase(record == null ? availableBase(audit.getContext()) : record.getAvailableBase())
                .setAvailableQuote(record == null ? availableQuote(audit.getContext()) : record.getAvailableQuote())
                .setExecutionStatus(record == null ? statusFromError(audit) : record.getExecutionStatus())
                .setSkipReason(record == null ? null : record.getSkipReason())
                .setError(firstText(record == null ? null : record.getError(), audit.getError()));
    }

    private void upsertAiRequest(AiDecisionAuditMapper mapper, AiDecisionAuditRecord audit, Long decisionRunId) {
        TradingDecisionContext context = audit.getContext();
        String parametersJson = context == null ? null : context.getAiParametersJson();
        if (isBlank(audit.getPrompt()) && isBlank(parametersJson)) {
            return;
        }

        mapper.upsertAiRequest(new AiRequestRow()
                .setDecisionRunId(decisionRunId)
                .setPromptText(audit.getPrompt())
                .setAiParametersJson(parametersJson));
    }

    private void upsertAiResponse(AiDecisionAuditMapper mapper, AiDecisionAuditRecord audit, Long decisionRunId) {
        AiTradingDecision decision = audit.getAiDecision();
        if (isBlank(audit.getRawAiResponse()) && decision == null) {
            return;
        }

        mapper.upsertAiResponse(new AiResponseRow()
                .setDecisionRunId(decisionRunId)
                .setReceivedAt(timestamp(audit.getCompletedAt()))
                .setRawResponse(audit.getRawAiResponse())
                .setParsedAction(decision == null || decision.getAction() == null ? null : decision.getAction().name())
                .setParsedReason(decision == null ? null : decision.getReason())
                .setParsedBuyQuoteAmount(decision == null ? null : decision.getBuyQuoteAmountUsdt())
                .setParsedSellBaseAmount(decision == null ? null : decision.getSellBaseAmountBtc())
                .setParsedOrderSize(decision == null ? null : decision.getOrderSize())
                .setParsedWinProbability(decision == null ? null : decision.getWinProbability())
                .setParsedConfidence(decision == null ? null : decision.getConfidence())
                .setParsedStrategyBias(decision == null ? null : decision.getStrategyBias())
                .setParsedStrategyThesis(decision == null ? null : decision.getStrategyThesis())
                .setParsedStrategyInvalidation(decision == null ? null : decision.getStrategyInvalidation())
                .setParsedStrategyHorizon(decision == null ? null : decision.getStrategyHorizon()));
    }

    private void upsertOrderExecution(AiDecisionAuditMapper mapper, AiDecisionAuditRecord audit, Long decisionRunId) {
        TradingDecisionRecord record = audit.getDecisionRecord();
        if (!shouldPersistOrder(record)) {
            return;
        }

        mapper.upsertOrderExecution(new OrderExecutionRow()
                .setDecisionRunId(decisionRunId)
                .setInstId(tradingProperties.getInstId())
                .setSide(orderSide(record))
                .setTdMode(tradingProperties.getTdMode())
                .setOrderType("market")
                .setTargetCurrency(targetCurrency(record))
                .setOrderSize(parseDecimal(record.getOrderSize()))
                .setOrderId(record.getOrderId())
                .setClientOrderId(record.getClientOrderId())
                .setExecutionStatus(record.getExecutionStatus())
                .setSkipReason(record.getSkipReason())
                .setFilledBaseAmount(record.getFilledBaseAmount())
                .setAverageFillPrice(record.getAverageFillPrice())
                .setFee(record.getFee())
                .setFeeCcy(record.getFeeCcy())
                .setError(record.getError()));
    }

    private boolean shouldPersistOrder(TradingDecisionRecord record) {
        if (record == null || record.getAction() == null || record.getAction() == TradingAction.HOLD) {
            return false;
        }
        return record.getOrderId() != null
                || record.getClientOrderId() != null
                || record.getOrderSize() != null
                || record.getSkipReason() != null
                || record.getError() != null
                || "SKIPPED".equals(record.getExecutionStatus())
                || "FAILED".equals(record.getExecutionStatus())
                || "FILLED".equals(record.getExecutionStatus())
                || "FILL_UNCONFIRMED".equals(record.getExecutionStatus());
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String actionName(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getAction() != null) {
            return record.getAction().name();
        }
        return decision == null || decision.getAction() == null ? null : decision.getAction().name();
    }

    private static String decisionReason(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getReason() != null) {
            return record.getReason();
        }
        return decision == null ? null : decision.getReason();
    }

    private static BigDecimal buyQuoteAmount(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getBuyQuoteAmountUsdt() != null) {
            return record.getBuyQuoteAmountUsdt();
        }
        return decision == null ? null : decision.getBuyQuoteAmountUsdt();
    }

    private static BigDecimal sellBaseAmount(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getSellBaseAmountBtc() != null) {
            return record.getSellBaseAmountBtc();
        }
        return decision == null ? null : decision.getSellBaseAmountBtc();
    }

    private static BigDecimal requestedOrderSize(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getRequestedOrderSize() != null) {
            return record.getRequestedOrderSize();
        }
        return decision == null ? null : decision.getOrderSize();
    }

    private static BigDecimal winProbability(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getWinProbability() != null) {
            return record.getWinProbability();
        }
        return decision == null ? null : decision.getWinProbability();
    }

    private static BigDecimal confidence(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getConfidence() != null) {
            return record.getConfidence();
        }
        return decision == null ? null : decision.getConfidence();
    }

    private static String strategyBias(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getStrategyBias() != null) {
            return record.getStrategyBias();
        }
        return decision == null ? null : decision.getStrategyBias();
    }

    private static String strategyThesis(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getStrategyThesis() != null) {
            return record.getStrategyThesis();
        }
        return decision == null ? null : decision.getStrategyThesis();
    }

    private static String strategyInvalidation(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getStrategyInvalidation() != null) {
            return record.getStrategyInvalidation();
        }
        return decision == null ? null : decision.getStrategyInvalidation();
    }

    private static String strategyHorizon(TradingDecisionRecord record, AiTradingDecision decision) {
        if (record != null && record.getStrategyHorizon() != null) {
            return record.getStrategyHorizon();
        }
        return decision == null ? null : decision.getStrategyHorizon();
    }

    private static String orderSide(TradingDecisionRecord record) {
        if (record == null || record.getAction() == null) {
            return null;
        }
        return switch (record.getAction()) {
            case BUY, OPEN_LONG, CLOSE_SHORT -> "buy";
            case SELL, CLOSE_LONG, OPEN_SHORT -> "sell";
            case HOLD -> null;
        };
    }

    private static String targetCurrency(TradingDecisionRecord record) {
        if (record == null || record.getAction() == null) {
            return null;
        }
        return switch (record.getAction()) {
            case BUY -> "quote_ccy";
            case SELL -> "base_ccy";
            case HOLD, OPEN_LONG, CLOSE_LONG, OPEN_SHORT, CLOSE_SHORT -> null;
        };
    }

    private static BigDecimal lastPrice(TradingDecisionContext context) {
        if (context == null || context.getTicker() == null) {
            return null;
        }
        return zeroToNull(TradingMath.decimal(context.getTicker().getLast()));
    }

    private static BigDecimal availableBase(TradingDecisionContext context) {
        return context == null ? null : zeroToNull(available(context.getBaseBalance()));
    }

    private static BigDecimal availableQuote(TradingDecisionContext context) {
        return context == null ? null : zeroToNull(available(context.getQuoteBalance()));
    }

    private static BigDecimal available(BalanceDetail detail) {
        if (detail == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal availBal = TradingMath.decimal(detail.getAvailBal());
        if (availBal.signum() > 0) {
            return availBal;
        }
        return TradingMath.decimal(detail.getCashBal());
    }

    private static BigDecimal zeroToNull(BigDecimal value) {
        return value == null || value.signum() == 0 ? null : value;
    }

    private static String statusFromError(AiDecisionAuditRecord audit) {
        return audit.getError() == null ? null : "FAILED";
    }

    private static String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Serialize AI decision audit JSON failed", e);
        }
    }

    private static BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
