package com.trade.trading.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.trading.config.AiTradeDatabaseProperties;
import com.trade.trading.config.AiTradingProperties;
import com.trade.trading.model.AiDecisionAuditRecord;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingTrigger;
import com.trade.trading.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MyBatisAiDecisionAuditRepository implements AiDecisionAuditSink {
    private static final Logger log = LoggerFactory.getLogger(MyBatisAiDecisionAuditRepository.class);

    private final ObjectProvider<AiDecisionAuditMapper> mapperProvider;
    private final AiTradeDatabaseProperties databaseProperties;
    private final AiTradingProperties tradingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final Object initializationMonitor = new Object();

    public MyBatisAiDecisionAuditRepository(
            ObjectProvider<AiDecisionAuditMapper> mapperProvider,
            AiTradeDatabaseProperties databaseProperties,
            AiTradingProperties tradingProperties
    ) {
        this.mapperProvider = mapperProvider;
        this.databaseProperties = databaseProperties;
        this.tradingProperties = tradingProperties;
    }

    @Override
    public void save(AiDecisionAuditRecord record) {
        if (!isEnabled() || record == null) {
            return;
        }

        AiDecisionAuditMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            log.debug("AI trade audit MyBatis mapper is not available, skip record persistence");
            return;
        }

        try {
            initializeIfNeeded(mapper);
            upsertDecisionRun(mapper, record);
            upsertAiRequest(mapper, record);
            upsertAiResponse(mapper, record);
            upsertOrderExecution(mapper, record);
        } catch (Exception e) {
            throw new IllegalStateException("Persist AI decision audit record failed", e);
        }
    }

    public void initializeSchema() {
        if (!isEnabled()) {
            log.info("AI trade audit database is not configured, skip schema initialization");
            return;
        }

        AiDecisionAuditMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            log.warn("AI trade audit MyBatis mapper is not available, skip schema initialization");
            return;
        }

        try {
            initializeIfNeeded(mapper);
        } catch (Exception e) {
            throw new IllegalStateException("Initialize AI trade audit schema failed", e);
        }
    }

    private boolean isEnabled() {
        return databaseProperties.isEnabled()
                && databaseProperties.getJdbcUrl() != null
                && !databaseProperties.getJdbcUrl().isBlank();
    }

    private void initializeIfNeeded(AiDecisionAuditMapper mapper) {
        if (!databaseProperties.isInitializeSchema() || initialized.get()) {
            return;
        }

        synchronized (initializationMonitor) {
            if (initialized.get()) {
                return;
            }

            mapper.createSchema(quoteIdentifier(schema()));
            mapper.createDecisionRunsTable(table("decision_runs"));
            mapper.createAiRequestsTable(table("ai_requests"), table("decision_runs"));
            mapper.createAiResponsesTable(table("ai_responses"), table("decision_runs"));
            mapper.createOrderExecutionsTable(table("order_executions"), table("decision_runs"));
            initialized.set(true);
            log.info("AI trade audit schema initialized by MyBatis: {}", schema());
        }
    }

    private void upsertDecisionRun(AiDecisionAuditMapper mapper, AiDecisionAuditRecord audit) {
        TradingDecisionRecord record = audit.getDecisionRecord();
        AiTradingDecision decision = audit.getAiDecision();
        TradingTrigger trigger = audit.getTrigger();

        mapper.upsertDecisionRun(new AiDecisionRunRow()
                .setTableName(table("decision_runs"))
                .setDecisionId(uuid(audit.getDecisionId()))
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
                .setLastPrice(record == null ? lastPrice(audit.getContext()) : record.getLastPrice())
                .setAvailableBase(record == null ? availableBase(audit.getContext()) : record.getAvailableBase())
                .setAvailableQuote(record == null ? availableQuote(audit.getContext()) : record.getAvailableQuote())
                .setExecutionStatus(record == null ? statusFromError(audit) : record.getExecutionStatus())
                .setSkipReason(record == null ? null : record.getSkipReason())
                .setError(firstText(record == null ? null : record.getError(), audit.getError())));
    }

    private void upsertAiRequest(AiDecisionAuditMapper mapper, AiDecisionAuditRecord audit) {
        TradingDecisionContext context = audit.getContext();
        String parametersJson = context == null ? null : context.getAiParametersJson();
        if (isBlank(audit.getPrompt()) && isBlank(parametersJson)) {
            return;
        }

        mapper.upsertAiRequest(new AiRequestRow()
                .setTableName(table("ai_requests"))
                .setDecisionId(uuid(audit.getDecisionId()))
                .setPromptText(audit.getPrompt())
                .setAiParametersJson(parametersJson));
    }

    private void upsertAiResponse(AiDecisionAuditMapper mapper, AiDecisionAuditRecord audit) {
        AiTradingDecision decision = audit.getAiDecision();
        if (isBlank(audit.getRawAiResponse()) && decision == null) {
            return;
        }

        mapper.upsertAiResponse(new AiResponseRow()
                .setTableName(table("ai_responses"))
                .setDecisionId(uuid(audit.getDecisionId()))
                .setReceivedAt(timestamp(audit.getCompletedAt()))
                .setRawResponse(audit.getRawAiResponse())
                .setParsedAction(decision == null || decision.getAction() == null ? null : decision.getAction().name())
                .setParsedReason(decision == null ? null : decision.getReason())
                .setParsedBuyQuoteAmount(decision == null ? null : decision.getBuyQuoteAmountUsdt())
                .setParsedSellBaseAmount(decision == null ? null : decision.getSellBaseAmountBtc()));
    }

    private void upsertOrderExecution(AiDecisionAuditMapper mapper, AiDecisionAuditRecord audit) {
        TradingDecisionRecord record = audit.getDecisionRecord();
        if (!shouldPersistOrder(record)) {
            return;
        }

        mapper.upsertOrderExecution(new OrderExecutionRow()
                .setTableName(table("order_executions"))
                .setDecisionId(uuid(audit.getDecisionId()))
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

    private String schema() {
        String schema = databaseProperties.getSchema();
        if (schema == null || schema.isBlank()) {
            schema = "ai_trade";
        }
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid AI trade database schema: " + schema);
        }
        return schema;
    }

    private String table(String tableName) {
        return quoteIdentifier(schema()) + "." + quoteIdentifier(tableName);
    }

    private static String quoteIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid MySQL identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }

    private static String uuid(UUID value) {
        return value == null ? null : value.toString();
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

    private static String orderSide(TradingDecisionRecord record) {
        if (record == null || record.getAction() == null) {
            return null;
        }
        return switch (record.getAction()) {
            case BUY -> "buy";
            case SELL -> "sell";
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
            case HOLD -> null;
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
