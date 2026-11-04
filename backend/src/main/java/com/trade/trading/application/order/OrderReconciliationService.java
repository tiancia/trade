package com.trade.trading.application.order;

import com.trade.trading.domain.risk.ReconciliationPolicy;
import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.AccountBalanceReq;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PositionResp;
import com.trade.client.okx.dto.PositionsReq;
import com.trade.trading.port.TradingOrderRepository;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.domain.model.OrderReconciliationStatus;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.order.TradingOrder;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.port.TradingStateStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuously converges unresolved local orders with OKX.
 *
 * <p>Reconciliation only queries deterministic {@code clOrdId}/{@code ordId}
 * identities and advances the durable order/fill ledger. It never calls
 * {@code placeOrder}; a retry after a crash therefore cannot duplicate an
 * external submission.</p>
 */
@Component
public class OrderReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);

    private final OkxApi okxApi;
    private final TradingOrderRepository orderRepository;
    private final OrderSettlementService settlementService;
    private final TradingStateStore stateRepository;
    private final FundSafetyService fundSafetyService;
    private final TradingProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastStartedAt;
    private volatile Instant lastCompletedAt;
    private volatile int lastCandidateCount;
    private volatile int lastReconciledCount;
    private volatile int consecutiveFailures;
    private volatile String lastError;

    public OrderReconciliationService(
            OkxApi okxApi,
            TradingOrderRepository orderRepository,
            OrderSettlementService settlementService,
            TradingStateStore stateRepository,
            FundSafetyService fundSafetyService,
            TradingProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.okxApi = okxApi;
        this.orderRepository = orderRepository;
        this.settlementService = settlementService;
        this.stateRepository = stateRepository;
        this.fundSafetyService = fundSafetyService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        Gauge.builder("trade.trading.reconciliation.running", running, value -> value.get() ? 1 : 0)
                .description("Whether an order reconciliation pass is running")
                .register(meterRegistry);
    }

    public void reconcileOnce() {
        if (!properties.isLiveAccountSelected()) {
            return;
        }
        // Reassert the exchange dead-man switch even when order reconciliation
        // is temporarily disabled. New submissions remain blocked by MySQL.
        fundSafetyService.enforceIfHalted();
        if (!properties.getReconciliation().isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            counter("busy").increment();
            return;
        }

        Timer.Sample timer = Timer.start(meterRegistry);
        Instant startedAt = Instant.now();
        lastStartedAt = startedAt;
        lastError = null;
        lastCandidateCount = 0;
        lastReconciledCount = 0;
        List<String> errors = new ArrayList<>();
        int reconciled = 0;
        try {
            List<TradingOrder> candidates = orderRepository.findReconciliationCandidates(
                    properties.getInstId(),
                    properties.getReconciliation().getBatchSize()
            );
            lastCandidateCount = candidates.size();
            for (TradingOrder candidate : candidates) {
                try {
                    OrderInfoResp exchangeOrder = queryOrder(candidate);
                    settlementService.applyExchangeSnapshot(candidate, exchangeOrder);
                    reconciled++;
                } catch (RuntimeException e) {
                    errors.add(candidate.getClientOrderId() + ": " + e.getMessage());
                    log.warn(
                            "Order reconciliation deferred: clOrdId={}, status={}, err={}",
                            candidate.getClientOrderId(),
                            candidate.getStatus(),
                            e.getMessage()
                    );
                }
            }
            lastReconciledCount = reconciled;
            if (!errors.isEmpty()) {
                throw new IllegalStateException(String.join("; ", errors));
            }
            // A balance can already include fills that are not yet checkpointed
            // in the order ledger. Comparing/adopting that balance while an
            // order is still working would race its next cumulative fill.
            if (!orderRepository.findReconciliationCandidates(properties.getInstId(), 1).isEmpty()) {
                counter("pending").increment();
                timer.stop(timer("pending"));
                return;
            }
            // A response completing after a stop must not make pre-stop observations
            // eligible for resume. Persist the start of the complete verification pass.
            reconcilePosition(startedAt);
            recordSuccess(startedAt);
            counter("completed").increment();
            timer.stop(timer("completed"));
        } catch (RuntimeException e) {
            recordFailure(e);
            counter("failed").increment();
            timer.stop(timer("failed"));
            if (ReconciliationPolicy.shouldHalt(consecutiveFailures,
                    properties.getReconciliation().getMaxConsecutiveFailures())) {
                fundSafetyService.halt(
                        "order-reconciliation",
                        "Reconciliation failed " + consecutiveFailures + " consecutive times: " + e.getMessage()
                );
            }
            throw e;
        } finally {
            lastCompletedAt = Instant.now();
            running.set(false);
        }
    }

    public OrderReconciliationStatus status() {
        return new OrderReconciliationStatus()
                .setEnabled(properties.getReconciliation().isEnabled())
                .setRunning(running.get())
                .setLastStartedAt(lastStartedAt)
                .setLastCompletedAt(lastCompletedAt)
                .setLastCandidateCount(lastCandidateCount)
                .setLastReconciledCount(lastReconciledCount)
                .setConsecutiveFailures(consecutiveFailures)
                .setLastError(lastError);
    }

    private OrderInfoResp queryOrder(TradingOrder candidate) {
        OkxResponse<OrderInfoResp> response = okxApi.getOrder(new OrderQueryReq()
                .setInstId(candidate.getInstId())
                .setOrdId(candidate.getExchangeOrderId())
                .setClOrdId(candidate.getClientOrderId()));
        return requireSingleSnapshot(response, "order reconciliation query");
    }

    private void reconcilePosition(Instant startedAt) {
        if (properties.isSpotInstrument()) {
            reconcileSpotBalance(startedAt);
            return;
        }
        OkxResponse<PositionResp> response = okxApi.getPositions(new PositionsReq()
                .setInstType(properties.getInstType())
                .setInstId(properties.getInstId()));
        OkxResponses.requireOk(response, "position reconciliation");
        if (response.getData() == null) {
            throw new IllegalStateException("OKX position reconciliation response has no position data");
        }
        List<PositionResp> positions = new ArrayList<>();
        for (PositionResp position : response.getData()) {
            if (position == null || !properties.getInstId().equals(position.getInstId())) {
                throw new IllegalStateException("OKX position reconciliation response has an invalid instrument identity");
            }
            if (requiredDecimal(position.getPos(), "position quantity").signum() != 0) {
                positions.add(position);
            }
        }
        ReconciliationPolicy.requireSinglePosition(positions.size());
        PositionResp position = positions.isEmpty() ? null : positions.getFirst();
        BigDecimal exchangeQuantity = position == null
                ? BigDecimal.ZERO
                : requiredDecimal(position.getPos(), "position quantity").abs();
        BigDecimal exchangeAverageCost = position == null
                ? BigDecimal.ZERO
                : requiredDecimal(position.getAvgPx(), "position average price");
        if (position != null && exchangeAverageCost.signum() <= 0) {
            throw new IllegalStateException("OKX nonzero position requires a positive average price");
        }
        recordAndCheckPosition(exchangeQuantity, exchangeAverageCost, startedAt);
    }

    private void reconcileSpotBalance(Instant startedAt) {
        OkxResponse<AccountBalanceResp> response = okxApi.getAccountBalance(new AccountBalanceReq()
                .setCcy(properties.getBaseCcy()));
        AccountBalanceResp account = requireSingleSnapshot(response, "spot balance reconciliation");
        if (account.getDetails() == null) {
            throw new IllegalStateException("OKX spot balance reconciliation response has no balance details");
        }
        BalanceDetail base = null;
        for (BalanceDetail detail : account.getDetails()) {
            if (detail == null || detail.getCcy() == null || detail.getCcy().isBlank()) {
                throw new IllegalStateException("OKX spot balance response has a missing currency identity");
            }
            if (properties.getBaseCcy().equalsIgnoreCase(detail.getCcy())) {
                if (base != null) {
                    throw new IllegalStateException("OKX spot balance response has duplicate base currency entries");
                }
                base = detail;
            }
        }
        BigDecimal exchangeQuantity = base == null
                ? BigDecimal.ZERO
                : requiredDecimal(base.getCashBal() == null || base.getCashBal().isBlank()
                        ? base.getEq() : base.getCashBal(), "spot balance");
        if (exchangeQuantity.signum() < 0) {
            throw new IllegalStateException("OKX spot balance reconciliation cannot verify a negative balance as owned assets");
        }
        recordAndCheckPosition(exchangeQuantity, null, startedAt);
    }

    private void recordAndCheckPosition(BigDecimal exchangeQuantity, BigDecimal exchangeAverageCost, Instant startedAt) {
        TradingState before = stateRepository.getState();
        boolean dedicated = properties.getReconciliation().getPositionOwnership()
                == TradingProperties.PositionOwnership.DEDICATED_ACCOUNT;
        stateRepository.recordExchangePosition(
                exchangeQuantity,
                // Spot ownership only advances through durable fill checkpoints.
                // Even a dedicated account must not import an uncheckpointed
                // fill (or a manual deposit) from a balance snapshot.
                dedicated && !properties.isSpotInstrument() ? exchangeQuantity : null,
                dedicated && !properties.isSpotInstrument() ? exchangeAverageCost : null,
                startedAt
        );
        String reason = ReconciliationPolicy.positionMismatch(dedicated, before.getTrackedBaseAmount(),
                exchangeQuantity, properties.getReconciliation().getPositionMismatchTolerance());
        if (reason != null) {
            fundSafetyService.halt("position-reconciliation", reason);
            throw new IllegalStateException(reason);
        }
    }

    private void recordSuccess(Instant startedAt) {
        TradingRiskState riskState = stateRepository.recordReconciliationSuccess(startedAt);
        consecutiveFailures = riskState.getConsecutiveReconciliationFailures();
    }

    private void recordFailure(RuntimeException error) {
        lastError = error.getMessage();
        TradingRiskState riskState = stateRepository.recordReconciliationFailure(
                Instant.now(),
                error.getMessage()
        );
        consecutiveFailures = riskState.getConsecutiveReconciliationFailures();
    }

    private Counter counter(String outcome) {
        return Counter.builder("trade.trading.reconciliation.runs")
                .description("Order reconciliation pass outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private Timer timer(String outcome) {
        return Timer.builder("trade.trading.reconciliation.duration")
                .description("Order reconciliation pass duration")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static <T> T requireSingleSnapshot(OkxResponse<T> response, String name) {
        OkxResponses.requireOk(response, name);
        if (response.getData() == null || response.getData().size() != 1 || response.getData().getFirst() == null) {
            throw new IllegalStateException("OKX " + name + " requires exactly one snapshot");
        }
        return response.getData().getFirst();
    }

    private static BigDecimal requiredDecimal(String value, String field) {
        // Lenient display parsing must never turn unverified exchange state into zero exposure.
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("OKX reconciliation response is missing " + field);
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("OKX reconciliation response has invalid " + field);
        }
    }

}
