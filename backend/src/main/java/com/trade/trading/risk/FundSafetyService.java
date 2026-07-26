package com.trade.trading.risk;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.CancelAllAfterReq;
import com.trade.client.okx.dto.CancelOrderReq;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.PendingOrdersReq;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.model.TradingState;
import com.trade.trading.order.TradingOrderRepository;
import com.trade.trading.persistence.TradingStateRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent capital-level kill switch for live trading.
 *
 * <p>Activation is fail-safe: HALTED is committed before any exchange calls.
 * New live submissions check this row, while best-effort cancellation and the
 * OKX cancel-all-after timer reduce already-open exposure. A restart cannot
 * clear the stop.</p>
 */
@Component
public class FundSafetyService {
    private static final Logger log = LoggerFactory.getLogger(FundSafetyService.class);
    private static final Set<String> HARD_RISK_CODES = Set.of(
            "RISK_MAX_DRAWDOWN",
            "RISK_DAILY_LOSS"
    );

    private final FundSafetyRepository repository;
    private final TradingOrderRepository orderRepository;
    private final TradingStateRepository stateRepository;
    private final OkxApi okxApi;
    private final TradingProperties properties;
    private final MeterRegistry meterRegistry;
    /** -1 means the initial database read failed or has not completed. */
    private final AtomicInteger statusGauge = new AtomicInteger(-1);

    public FundSafetyService(
            FundSafetyRepository repository,
            TradingOrderRepository orderRepository,
            TradingStateRepository stateRepository,
            OkxApi okxApi,
            TradingProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.stateRepository = stateRepository;
        this.okxApi = okxApi;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        Gauge.builder("trade.trading.fund_safety.status", statusGauge, AtomicInteger::get)
                .description("Persistent fund safety status: -1 unknown, 0 active, 1 halted")
                .register(meterRegistry);
    }

    public FundSafetyState state() {
        return observe(repository.getOrCreate(properties.financialAccountScope()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeStatusGauge() {
        try {
            FundSafetyState current = state();
            if (current.isHalted() && properties.isLiveAccountSelected()) {
                cancelOutstandingOrders();
            }
        } catch (RuntimeException e) {
            // Keep -1 so monitoring distinguishes an unreadable safety row
            // from an ACTIVE state. Live submissions still fail closed because
            // requireActive performs its own authoritative read.
            log.error("Initialize persistent fund safety status failed", e);
        }
    }

    /**
     * Reasserts exchange-side cancellation while the persistent stop remains
     * active. The recovery loop calls this periodically, so a process restart
     * or an expired cancel-all-after timer cannot silently weaken the stop.
     */
    public boolean enforceIfHalted() {
        FundSafetyState current = state();
        if (!current.isHalted()) {
            return false;
        }
        if (properties.isLiveAccountSelected()) {
            cancelOutstandingOrders();
        }
        return true;
    }

    public void requireActive() {
        FundSafetyState state = state();
        if (state.isHalted()) {
            throw new TradingFundsHaltedException(
                    "Live trading halted: " + firstText(state.getReason(), "operator or safety stop")
            );
        }
    }

    public FundSafetyState halt(String source, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Fund stop reason is required");
        }
        FundSafetyState state = repository.halt(
                properties.financialAccountScope(),
                firstText(source, "unknown"),
                reason.trim(),
                Instant.now()
        );
        observe(state);
        counter("halted").increment();
        if (properties.isLiveAccountSelected()) {
            cancelOutstandingOrders();
        }
        return state();
    }

    public FundSafetyState haltForHardRisk(RiskAssessment assessment) {
        if (assessment == null || assessment.getViolations() == null) {
            return state();
        }
        return assessment.getViolations().stream()
                .filter(violation -> violation != null && HARD_RISK_CODES.contains(violation.getCode()))
                .findFirst()
                .map(violation -> halt("risk-control", violation.getReason()))
                .orElseGet(this::state);
    }

    public FundSafetyState resume(long expectedVersion, String reason, String confirmation) {
        if (!properties.getFundSafety().getResumeConfirmation().equals(confirmation)) {
            throw new IllegalArgumentException("Invalid live trading resume confirmation");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Resume reason is required");
        }
        FundSafetyState current = state();
        if (!current.isHalted()) {
            throw new IllegalStateException("Fund safety is already ACTIVE");
        }
        if (current.getVersion() != expectedVersion) {
            throw new java.util.ConcurrentModificationException(
                    "Fund safety revision changed from " + expectedVersion + " to " + current.getVersion()
            );
        }
        if (properties.isLiveAccountSelected()) {
            List<OrderInfoResp> pending = pendingOrders();
            if (!pending.isEmpty()) {
                throw new IllegalStateException("Cannot resume while OKX still has pending orders");
            }
            if (!orderRepository.findReconciliationCandidates(
                    properties.getInstId(),
                    properties.getReconciliation().getBatchSize()
            ).isEmpty()) {
                throw new IllegalStateException("Cannot resume while local orders still require reconciliation");
            }
            requireFreshSuccessfulReconciliation(current);
            // Disarm the exchange timer before reopening local submissions. If
            // this call fails, the database row remains HALTED.
            requireActionOk(
                    okxApi.cancelAllAfter(new CancelAllAfterReq().setTimeOut("0")),
                    "disarm cancel-all-after"
            );
        }
        FundSafetyState state = repository.resume(
                properties.financialAccountScope(),
                expectedVersion,
                reason.trim(),
                Instant.now()
        );
        observe(state);
        counter("resumed").increment();
        return state;
    }

    private void requireFreshSuccessfulReconciliation(FundSafetyState safetyState) {
        TradingState tradingState = stateRepository.getState();
        TradingRiskState riskState = tradingState.getRiskState();
        Instant positionAt = parseInstant(tradingState.getPositionLastReconciledAt());
        Instant riskAt = parseInstant(riskState == null ? null : riskState.getLastReconciliationAt());
        Instant haltedAt = safetyState.getHaltedAt();
        boolean stalePosition = positionAt == null || (haltedAt != null && positionAt.isBefore(haltedAt));
        boolean staleRisk = riskAt == null || (haltedAt != null && riskAt.isBefore(haltedAt));
        boolean failed = riskState == null
                || riskState.getConsecutiveReconciliationFailures() != 0
                || riskState.getLastReconciliationError() != null;
        if (stalePosition || staleRisk || failed) {
            throw new IllegalStateException(
                    "Cannot resume before a successful order and position reconciliation after the fund stop"
            );
        }
    }

    private void cancelOutstandingOrders() {
        try {
            int timeout = Math.max(1, properties.getFundSafety().getDeadManTimeoutSeconds());
            requireActionOk(
                    okxApi.cancelAllAfter(new CancelAllAfterReq().setTimeOut(String.valueOf(timeout))),
                    "arm cancel-all-after"
            );
            for (OrderInfoResp order : pendingOrders()) {
                OkxResponse<OrderActionResp> response = okxApi.cancelOrder(new CancelOrderReq()
                        .setInstId(properties.getInstId())
                        .setOrdId(order.getOrdId())
                        .setClOrdId(order.getClOrdId()));
                requireActionOk(response, "cancel pending order");
            }
            repository.recordActionError(properties.financialAccountScope(), null);
        } catch (RuntimeException e) {
            // HALTED was persisted first, so cancellation failure cannot reopen
            // submissions. Surface the error for operator remediation.
            repository.recordActionError(properties.financialAccountScope(), e.getMessage());
            counter("cancel_failed").increment();
            log.error("Fund stop persisted but exchange cancellation failed", e);
        }
    }

    private List<OrderInfoResp> pendingOrders() {
        OkxResponse<OrderInfoResp> response = okxApi.getPendingOrders(new PendingOrdersReq()
                .setInstType(properties.getInstType())
                .setInstId(properties.getInstId())
                .setLimit(String.valueOf(properties.getReconciliation().getBatchSize())));
        OkxResponses.requireOk(response, "pending order query");
        return response == null || response.getData() == null ? List.of() : List.copyOf(response.getData());
    }

    private Counter counter(String outcome) {
        return Counter.builder("trade.trading.fund_safety.actions")
                .description("Persistent fund-level stop actions")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private FundSafetyState observe(FundSafetyState state) {
        if (state != null) {
            statusGauge.set(state.isHalted() ? 1 : 0);
        }
        return state;
    }

    private static void requireActionOk(OkxResponse<OrderActionResp> response, String action) {
        OkxResponses.requireOk(response, action);
        OkxResponses.first(response).ifPresent(item -> {
            if (item.getSCode() != null && !"0".equals(item.getSCode())) {
                throw new IllegalStateException(
                        "OKX " + action + " rejected, sCode=" + item.getSCode()
                                + ", sMsg=" + item.getSMsg()
                );
            }
        });
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static final class TradingFundsHaltedException extends IllegalStateException {
        public TradingFundsHaltedException(String message) {
            super(message);
        }
    }
}
