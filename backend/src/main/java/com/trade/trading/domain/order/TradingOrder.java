package com.trade.trading.domain.order;

import lombok.Builder;
import lombok.Getter;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable order. Only named domain changes may advance a live order. */
@Getter
@EqualsAndHashCode
@ToString
public final class TradingOrder {
    private final Long id;
    private final String idempotencyKey;
    private final String clientOrderId;
    private final String exchangeOrderId;
    private final String decisionId;
    private final String strategyId;
    private final String instId;
    private final String action;
    private final String side;
    private final String tdMode;
    private final String orderType;
    private final String targetCurrency;
    private final BigDecimal requestedSize;
    private final OrderStatus status;
    private final long version;
    private final BigDecimal filledBaseAmount;
    private final BigDecimal averageFillPrice;
    private final BigDecimal fee;
    private final String feeCcy;
    private final String failureCode;
    private final String failureMessage;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant submittedAt;
    private final Instant completedAt;

    private TradingOrder(Snapshot snapshot) {
        this.id = snapshot.id();
        this.idempotencyKey = snapshot.idempotencyKey();
        this.clientOrderId = snapshot.clientOrderId();
        this.exchangeOrderId = snapshot.exchangeOrderId();
        this.decisionId = snapshot.decisionId();
        this.strategyId = snapshot.strategyId();
        this.instId = snapshot.instId();
        this.action = snapshot.action();
        this.side = snapshot.side();
        this.tdMode = snapshot.tdMode();
        this.orderType = snapshot.orderType();
        this.targetCurrency = snapshot.targetCurrency();
        this.requestedSize = snapshot.requestedSize();
        this.status = snapshot.status();
        this.version = snapshot.version();
        this.filledBaseAmount = snapshot.filledBaseAmount();
        this.averageFillPrice = snapshot.averageFillPrice();
        this.fee = snapshot.fee();
        this.feeCcy = snapshot.feeCcy();
        this.failureCode = snapshot.failureCode();
        this.failureMessage = snapshot.failureMessage();
        this.createdAt = snapshot.createdAt();
        this.updatedAt = snapshot.updatedAt();
        this.submittedAt = snapshot.submittedAt();
        this.completedAt = snapshot.completedAt();
    }

    public static TradingOrder pending(OrderSubmission submission, Instant now) {
        return new TradingOrder(Snapshot.builder()
                .idempotencyKey(submission.idempotencyKey())
                .clientOrderId(submission.clientOrderId())
                .decisionId(submission.decisionId())
                .strategyId(submission.strategyId())
                .instId(submission.instId())
                .action(submission.action())
                .side(submission.side())
                .tdMode(submission.tdMode())
                .orderType(submission.orderType())
                .targetCurrency(submission.targetCurrency())
                .requestedSize(submission.requestedSize())
                .status(OrderStatus.PENDING_SUBMIT).version(0).createdAt(now).updatedAt(now).build());
    }

    /** Persistence rehydration only; not a business state-change API. */
    public static TradingOrder restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot.status(), "Persisted order status is required");
        if (snapshot.version() < 0) { throw new IllegalArgumentException("Order version must not be negative"); }
        return new TradingOrder(snapshot);
    }

    public Snapshot snapshot() {
        return Snapshot.builder()
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
                .completedAt(completedAt).build();
    }

    TradingOrder change(OrderStatus target, Consumer<Snapshot.SnapshotBuilder> mutation, Instant now) {
        if (status != target) { OrderStateMachine.requireTransition(status, target); }
        var next = snapshot().toBuilder().status(target).version(version + 1).updatedAt(now);
        mutation.accept(next);
        return new TradingOrder(next.build());
    }

    /** Detached persistence snapshot; changing it never mutates an existing order. */
    @Builder(toBuilder = true)
    public record Snapshot(
            Long id,
            String idempotencyKey,
            String clientOrderId,
            String exchangeOrderId,
            String decisionId,
            String strategyId,
            String instId,
            String action,
            String side,
            String tdMode,
            String orderType,
            String targetCurrency,
            BigDecimal requestedSize,
            OrderStatus status,
            long version,
            BigDecimal filledBaseAmount,
            BigDecimal averageFillPrice,
            BigDecimal fee,
            String feeCcy,
            String failureCode,
            String failureMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant submittedAt,
            Instant completedAt
    ) {}
}
