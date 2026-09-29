package com.trade.trading.infrastructure.persistence;

import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.TradingOrder;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

/** Mutable MyBatis mapping; never exposed to application or domain callers. */
@Data
@Accessors(chain = true)
public class TradingOrderRow {
    private Long id;
    private String idempotencyKey;
    private String clientOrderId;
    private String exchangeOrderId;
    private String decisionId;
    private String strategyId;
    private String instId;
    private String action;
    private String side;
    private String tdMode;
    private String orderType;
    private String targetCurrency;
    private BigDecimal requestedSize;
    private OrderStatus status;
    private long version;
    private BigDecimal filledBaseAmount;
    private BigDecimal averageFillPrice;
    private BigDecimal fee;
    private String feeCcy;
    private String failureCode;
    private String failureMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant submittedAt;
    private Instant completedAt;

    TradingOrder toDomain() {
        return TradingOrder.restore(TradingOrder.Snapshot.builder()
                .id(id)
                .idempotencyKey(idempotencyKey)
                .clientOrderId(clientOrderId)
                .exchangeOrderId(exchangeOrderId)
                .decisionId(decisionId)
                .strategyId(strategyId)
                .instId(instId)
                .action(action)
                .side(side)
                .tdMode(tdMode)
                .orderType(orderType)
                .targetCurrency(targetCurrency)
                .requestedSize(requestedSize)
                .status(status)
                .version(version)
                .filledBaseAmount(filledBaseAmount)
                .averageFillPrice(averageFillPrice)
                .fee(fee)
                .feeCcy(feeCcy)
                .failureCode(failureCode)
                .failureMessage(failureMessage)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .submittedAt(submittedAt)
                .completedAt(completedAt).build());
    }

    static TradingOrderRow fromDomain(TradingOrder order) {
        return new TradingOrderRow()
                .setId(order.getId())
                .setIdempotencyKey(order.getIdempotencyKey())
                .setClientOrderId(order.getClientOrderId())
                .setExchangeOrderId(order.getExchangeOrderId())
                .setDecisionId(order.getDecisionId())
                .setStrategyId(order.getStrategyId())
                .setInstId(order.getInstId())
                .setAction(order.getAction())
                .setSide(order.getSide())
                .setTdMode(order.getTdMode())
                .setOrderType(order.getOrderType())
                .setTargetCurrency(order.getTargetCurrency())
                .setRequestedSize(order.getRequestedSize())
                .setStatus(order.getStatus())
                .setVersion(order.getVersion())
                .setFilledBaseAmount(order.getFilledBaseAmount())
                .setAverageFillPrice(order.getAverageFillPrice())
                .setFee(order.getFee())
                .setFeeCcy(order.getFeeCcy())
                .setFailureCode(order.getFailureCode())
                .setFailureMessage(order.getFailureMessage())
                .setCreatedAt(order.getCreatedAt())
                .setUpdatedAt(order.getUpdatedAt())
                .setSubmittedAt(order.getSubmittedAt())
                .setCompletedAt(order.getCompletedAt());
    }
}
