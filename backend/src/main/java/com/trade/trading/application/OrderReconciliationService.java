package com.trade.trading.application;

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
import com.trade.common.support.TradingMath;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.model.TradingState;
import com.trade.trading.order.OrderSettlementService;
import com.trade.trading.order.TradingOrder;
import com.trade.trading.order.TradingOrderRepository;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.risk.FundSafetyService;
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
    private final TradingStateRepository stateRepository;
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
            TradingStateRepository stateRepository,
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
        lastStartedAt = Instant.now();
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
            reconcilePosition();
            lastReconciledCount = reconciled;
            if (!errors.isEmpty()) {
                throw new IllegalStateException(String.join("; ", errors));
            }
            recordSuccess();
            counter("completed").increment();
            timer.stop(timer("completed"));
        } catch (RuntimeException e) {
            recordFailure(e);
            counter("failed").increment();
            timer.stop(timer("failed"));
            if (consecutiveFailures >= Math.max(
                    1,
                    properties.getReconciliation().getMaxConsecutiveFailures()
            )) {
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
        OkxResponses.requireOk(response, "order reconciliation query");
        return OkxResponses.first(response)
                .orElseThrow(() -> new IllegalStateException(
                        "OKX returned no order for clOrdId=" + candidate.getClientOrderId()
                ));
    }

    private void reconcilePosition() {
        if (properties.isSpotInstrument()) {
            reconcileSpotBalance();
            return;
        }
        OkxResponse<PositionResp> response = okxApi.getPositions(new PositionsReq()
                .setInstType(properties.getInstType())
                .setInstId(properties.getInstId()));
        OkxResponses.requireOk(response, "position reconciliation");
        List<PositionResp> positions = response == null || response.getData() == null
                ? List.of()
                : response.getData().stream()
                .filter(position -> properties.getInstId().equals(position.getInstId()))
                .filter(position -> TradingMath.decimal(position.getPos()).signum() != 0)
                .toList();
        if (positions.size() > 1) {
            throw new IllegalStateException(
                    "Multiple derivative position sides require an explicit portfolio projection"
            );
        }
        PositionResp position = positions.isEmpty() ? null : positions.getFirst();
        BigDecimal exchangeQuantity = position == null
                ? BigDecimal.ZERO
                : TradingMath.decimal(position.getPos()).abs();
        BigDecimal exchangeAverageCost = position == null
                ? BigDecimal.ZERO
                : TradingMath.decimal(position.getAvgPx());
        recordAndCheckPosition(exchangeQuantity, exchangeAverageCost);
    }

    private void reconcileSpotBalance() {
        OkxResponse<AccountBalanceResp> response = okxApi.getAccountBalance(new AccountBalanceReq()
                .setCcy(properties.getBaseCcy()));
        OkxResponses.requireOk(response, "spot balance reconciliation");
        AccountBalanceResp account = OkxResponses.first(response).orElse(null);
        BalanceDetail base = account == null || account.getDetails() == null
                ? null
                : account.getDetails().stream()
                .filter(detail -> properties.getBaseCcy().equalsIgnoreCase(detail.getCcy()))
                .findFirst()
                .orElse(null);
        BigDecimal exchangeQuantity = base == null
                ? BigDecimal.ZERO
                : firstPositive(base.getCashBal(), base.getEq());
        recordAndCheckPosition(exchangeQuantity, null);
    }

    private void recordAndCheckPosition(BigDecimal exchangeQuantity, BigDecimal exchangeAverageCost) {
        TradingState before = stateRepository.getState();
        boolean dedicated = properties.getReconciliation().getPositionOwnership()
                == TradingProperties.PositionOwnership.DEDICATED_ACCOUNT;
        stateRepository.recordExchangePosition(
                exchangeQuantity,
                dedicated ? exchangeQuantity : null,
                dedicated ? exchangeAverageCost : null,
                Instant.now()
        );
        if (!dedicated) {
            return;
        }
        BigDecimal difference = zero(before.getTrackedBaseAmount())
                .subtract(zero(exchangeQuantity))
                .abs();
        BigDecimal tolerance = zero(properties.getReconciliation().getPositionMismatchTolerance());
        if (difference.compareTo(tolerance) > 0) {
            String reason = "Dedicated-account position mismatch: managed="
                    + before.getTrackedBaseAmount() + ", exchange=" + exchangeQuantity;
            fundSafetyService.halt("position-reconciliation", reason);
            throw new IllegalStateException(reason);
        }
    }

    private void recordSuccess() {
        TradingRiskState riskState = stateRepository.recordReconciliationSuccess(Instant.now());
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

    private static BigDecimal firstPositive(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return TradingMath.decimal(first).max(BigDecimal.ZERO);
        }
        return TradingMath.decimal(fallback).max(BigDecimal.ZERO);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
