package com.trade.trading.application.order;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PendingOrdersReq;
import com.trade.trading.port.TradingOrderRepository;
import com.trade.trading.port.TradingStateStore;
import com.trade.trading.port.FundSafetyRepository;
import com.trade.trading.port.LiveAccountLock;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.domain.order.OrderChange;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.TradingOrder;
import com.trade.trading.domain.risk.FundSafetyPolicy;
import com.trade.trading.infrastructure.config.TradingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;

/** Explicit operator recovery for an unknown submit; never retries an order. */
@Component
public class UnknownOrderRecoveryService {
    public static final String CONFIRMATION = "I_VERIFIED_HISTORY_FILLS_AND_ASSETS_ORDER_NOT_ACCEPTED";
    private static final Duration MINIMUM_AGE = Duration.ofMinutes(30);
    private final TradingOrderRepository orders;
    private final TradingStateStore state;
    private final FundSafetyRepository safety;
    private final LiveAccountLock accountLock;
    private final TradingLeadershipService leadership;
    private final TradingProperties properties;
    private final OkxApi okx;
    private final MeterRegistry meters;

    public UnknownOrderRecoveryService(TradingOrderRepository orders, TradingStateStore state,
            FundSafetyRepository safety, TradingLeadershipService leadership, TradingProperties properties,
            OkxApi okx, MeterRegistry meters, LiveAccountLock accountLock) {
        this.orders = orders;
        this.state = state;
        this.safety = safety;
        this.accountLock = accountLock;
        this.leadership = leadership;
        this.properties = properties;
        this.okx = okx;
        this.meters = meters;
    }

    public List<TradingOrder> unresolved() {
        return orders.findReconciliationCandidates(properties.getInstId(),
                properties.getReconciliation().getBatchSize());
    }

    public TradingOrder abandon(String key, long expectedVersion, long expectedSafetyVersion,
            String clientOrderId, String operatorReference, String reason, String evidence,
            String confirmation) {
        if (!CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("Explicit verification of account order history, fills and assets is required");
        }
        String audit = "Operator " + required(operatorReference, "operatorReference", 30)
                + "; reason=" + required(reason, "reason", 70)
                + "; evidence=" + required(evidence, "evidence", 100);
        if (!properties.isLiveAccountSelected()) {
            throw new IllegalStateException("Unknown order recovery requires the original LIVE account configuration");
        }
        // The same database guard covers a submitting worker from reservation to acknowledgement.
        return accountLock.withLock(() -> abandonLocked(key, expectedVersion, expectedSafetyVersion, clientOrderId, audit));
    }

    private TradingOrder abandonLocked(String key, long expectedVersion, long expectedSafetyVersion,
                                       String clientOrderId, String audit) {
        String scope = properties.financialAccountScope();
        leadership.requireLiveLeadership();
        FundSafetyPolicy.requireResumeState(safety.getOrCreate(scope), expectedSafetyVersion);
        TradingOrder current = orders.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (current.getVersion() != expectedVersion) {
            throw new ConcurrentModificationException("Order version changed; inspect it again");
        }
        if (!properties.getInstId().equals(current.getInstId())
                || (current.getStatus() != OrderStatus.SUBMIT_UNKNOWN && current.getStatus() != OrderStatus.SUBMITTING)
                || current.getClientOrderId() == null || !current.getClientOrderId().equals(clientOrderId)
                || hasText(current.getExchangeOrderId())
                || current.getFilledBaseAmount() != null && current.getFilledBaseAmount().signum() != 0) {
            throw new IllegalStateException("Only the matching unknown order without an exchange ID or fills can be abandoned");
        }
        Instant checkedAt = Instant.now();
        if (current.getSubmittedAt() == null || current.getSubmittedAt().plus(MINIMUM_AGE).isAfter(checkedAt)
                || current.getUpdatedAt() == null || current.getUpdatedAt().plus(MINIMUM_AGE).isAfter(checkedAt)) {
            throw new IllegalStateException("Wait at least 30 minutes after submission and the last order change; stop the submitting worker first");
        }
        var lookup = okx.getOrder(new OrderQueryReq().setInstId(current.getInstId())
                .setClOrdId(current.getClientOrderId()));
        if (lookup == null || !"51603".equals(lookup.getCode())
                || lookup.getData() != null && !lookup.getData().isEmpty()) {
            throw new IllegalStateException("Recovery requires a current OKX 51603 response; reconcile any returned order instead");
        }
        var pending = okx.getPendingOrders(new PendingOrdersReq().setInstType(properties.getInstType())
                .setInstId(current.getInstId()).setLimit("100"));
        OkxResponses.requireOk(pending, "unknown order recovery pending orders");
        if (pending.getData() == null || !pending.getData().isEmpty()) {
            throw new IllegalStateException("Cannot abandon while pending orders exist or their absence cannot be verified");
        }
        // 51603 does not prove non-acceptance: the explicit human attestation and
        // evidence above remain necessary. Keep HALTED and force a fresh full
        // order/position reconciliation before the separate resume operation.
        leadership.requireLiveLeadership();
        Instant now = Instant.now();
        OrderChange change = OrderChange.abandonUnknown(audit, now);
        TradingOrder next = change.apply(current, now);
        safety.recordOrderRecovery(scope, expectedSafetyVersion, () -> {
            if (!orders.compareAndSet(current, next, audit)) {
                throw new ConcurrentModificationException("Order changed during recovery; no abandonment was committed");
            }
            state.recordReconciliationFailure(now, "Unknown order manually abandoned; fresh full reconciliation required");
        });
        meters.counter("trade.trading.orders.manual_recovery", "outcome", "abandoned").increment();
        return next;
    }

    private static String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must contain 1-" + maxLength + " characters without control characters");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
