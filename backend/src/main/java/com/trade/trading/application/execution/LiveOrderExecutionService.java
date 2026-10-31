package com.trade.trading.application.execution;

import com.trade.trading.domain.order.ExecutionEligibility;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.trading.application.execution.OrderSizingService;
import com.trade.trading.application.order.OrderIdempotencyKeyFactory;
import com.trade.trading.application.order.OrderLifecycleService;
import com.trade.trading.application.order.OrderSettlementService;
import com.trade.trading.port.ExchangeOrderGateway;
import com.trade.trading.port.ExchangeOrderGateway.OrderRejectedException;
import com.trade.trading.port.TradingStateStore;
import com.trade.trading.application.risk.FundSafetyService;
import com.trade.trading.application.risk.RiskControlService;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.order.OrderReservation;
import com.trade.trading.domain.order.OrderSettlementResult;
import com.trade.trading.domain.order.OrderStatus;
import com.trade.trading.domain.order.OrderSubmission;
import com.trade.trading.domain.order.TradingOrder;
import com.trade.trading.domain.risk.RiskAssessment;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.trade.trading.application.strategy.TradingStrategyMaintenance;

import java.math.BigDecimal;
import java.util.Optional;

@Component
public class LiveOrderExecutionService {
    private static final Logger log = LoggerFactory.getLogger(LiveOrderExecutionService.class);

    private final ExchangeOrderGateway orderGateway;
    private final OrderSizingService orderSizingService;
    private final TradingProperties properties;
    private final RiskControlService riskControlService;
    private final FundSafetyService fundSafetyService;
    private final TradingLeadershipService leadershipService;
    private final OrderLifecycleService orderLifecycleService;
    private final OrderSettlementService orderSettlementService;
    private final OrderIdempotencyKeyFactory idempotencyKeyFactory;
    private final TradingStateStore stateRepository;
    private final TradingStrategyMaintenance maintenance;

    public LiveOrderExecutionService(
            ExchangeOrderGateway orderGateway,
            OrderSizingService orderSizingService,
            TradingProperties properties,
            RiskControlService riskControlService,
            FundSafetyService fundSafetyService,
            TradingLeadershipService leadershipService,
            OrderLifecycleService orderLifecycleService,
            OrderSettlementService orderSettlementService,
            OrderIdempotencyKeyFactory idempotencyKeyFactory,
            TradingStateStore stateRepository
    ) {
        this(orderGateway, orderSizingService, properties, riskControlService, fundSafetyService, leadershipService,
                orderLifecycleService, orderSettlementService, idempotencyKeyFactory, stateRepository, null);
    }

    @Autowired
    public LiveOrderExecutionService(ExchangeOrderGateway orderGateway, OrderSizingService orderSizingService,
            TradingProperties properties, RiskControlService riskControlService, FundSafetyService fundSafetyService,
            TradingLeadershipService leadershipService, OrderLifecycleService orderLifecycleService,
            OrderSettlementService orderSettlementService, OrderIdempotencyKeyFactory idempotencyKeyFactory,
            TradingStateStore stateRepository, TradingStrategyMaintenance maintenance) {
        this.orderGateway = orderGateway;
        this.orderSizingService = orderSizingService;
        this.properties = properties;
        this.riskControlService = riskControlService;
        this.fundSafetyService = fundSafetyService;
        this.leadershipService = leadershipService;
        this.orderLifecycleService = orderLifecycleService;
        this.orderSettlementService = orderSettlementService;
        this.idempotencyKeyFactory = idempotencyKeyFactory;
        this.stateRepository = stateRepository;
        this.maintenance = maintenance;
    }

    public void execute(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        if (!properties.isLiveExecutionAllowed()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason("Live OKX order blocked: execution-mode=live and live-enabled=true are both required");
            return;
        }
        if (!properties.isSpotInstrument()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(
                            "Live derivative order blocked: contract, margin, and funding settlement "
                                    + "must be ledgered before real derivative submissions are enabled"
                    );
            return;
        }
        try {
            leadershipService.requireLiveSubmissionLeadership();
        } catch (TradingLeadershipService.TradingLeadershipUnavailableException e) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(e.getMessage());
            return;
        }
        try {
            fundSafetyService.requireActive();
        } catch (FundSafetyService.TradingFundsHaltedException e) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(e.getMessage());
            return;
        }

        RiskAssessment riskAssessment = riskControlService.evaluate(decision, context);
        if (!riskAssessment.isAllowed()) {
            // Daily loss and drawdown block opens through the risk rules. They
            // must not latch the all-orders emergency stop and disable exits.
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(riskAssessment.skipReason());
            log.info("{}", riskAssessment.skipReason());
            return;
        }

        if (decision.getAction() == TradingAction.BUY) {
            executeBuy(decision, context, decisionRecord);
        } else if (decision.getAction() == TradingAction.SELL) {
            executeSell(decision, context, decisionRecord);
        } else if (decision.getAction() != null && decision.getAction().isDerivativeAction()) {
            executeDerivative(decision, context, decisionRecord);
        } else {
            decisionRecord.setExecutionStatus("HELD");
            log.info("Strategy decision HOLD, no order placed. reason={}", decision.getReason());
        }
    }

    private void executeBuy(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        String skipReason = ExecutionEligibility.skipReason(decision.getAction(),
                properties.isSpotInstrument(), properties.isShortEnabled());
        if (skipReason != null) {
            decisionRecord.setExecutionStatus("SKIPPED").setSkipReason(skipReason);
            return;
        }
        OrderSizing sizing = orderSizingService.buySize(decision, context);
        if (!sizing.isExecutable()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(sizing.getSkipReason());
            return;
        }

        PlaceOrderReq req = new PlaceOrderReq()
                .setInstId(properties.getInstId())
                .setTdMode(properties.getTdMode())
                .setSide("buy")
                .setOrdType("market")
                .setTgtCcy("quote_ccy")
                .setSz(sizing.getSize())
                .setTag("strategyTrade");
        decisionRecord.setOrderSize(sizing.getSize());
        executeOrder(decision, context, decisionRecord, req);
    }

    private void executeDerivative(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        String skipReason = ExecutionEligibility.skipReason(decision.getAction(),
                properties.isSpotInstrument(), properties.isShortEnabled());
        if (skipReason != null) {
            decisionRecord.setExecutionStatus("SKIPPED").setSkipReason(skipReason);
            return;
        }
        OrderSizing sizing = orderSizingService.derivativeSize(decision, context);
        if (!sizing.isExecutable()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(sizing.getSkipReason());
            return;
        }

        DerivativeOrder order = derivativeOrder(decision.getAction());
        PlaceOrderReq req = new PlaceOrderReq()
                .setInstId(properties.getInstId())
                .setTdMode(properties.getTdMode())
                .setSide(order.side())
                .setOrdType("market")
                .setSz(sizing.getSize())
                .setTag("strategyTrade");
        if (properties.isLongShortPositionMode()) {
            req.setPosSide(order.posSide());
        }
        if (order.reduceOnly() && !properties.isLongShortPositionMode()) {
            req.setReduceOnly("true");
        }
        decisionRecord.setOrderSize(sizing.getSize());
        executeOrder(decision, context, decisionRecord, req);
    }

    private void executeSell(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        String skipReason = ExecutionEligibility.skipReason(decision.getAction(),
                properties.isSpotInstrument(), properties.isShortEnabled());
        if (skipReason != null) {
            decisionRecord.setExecutionStatus("SKIPPED").setSkipReason(skipReason);
            return;
        }
        // A decision's account snapshot can include manual holdings or be stale.
        OrderSizing sizing = orderSizingService.sellSize(decision, context, managedQuantity());
        if (!sizing.isExecutable()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(sizing.getSkipReason());
            return;
        }

        PlaceOrderReq req = new PlaceOrderReq()
                .setInstId(properties.getInstId())
                .setTdMode(properties.getTdMode())
                .setSide("sell")
                .setOrdType("market")
                .setTgtCcy("base_ccy")
                .setSz(sizing.getSize())
                .setTag("strategyTrade");
        decisionRecord.setOrderSize(sizing.getSize());
        executeOrder(decision, context, decisionRecord, req);
    }

    private void executeOrder(
            StrategyDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord record,
            PlaceOrderReq request
    ) {
        String idempotencyKey = idempotencyKeyFactory.create(
                properties,
                decision,
                context,
                record,
                request.getSz()
        );
        String clientOrderId = idempotencyKeyFactory.clientOrderId(
                idempotencyKey,
                decision.getAction().name()
        );
        request.setClOrdId(clientOrderId);
        if (decision.getMetadata() != null && decision.getMetadata().containsKey("signalBar")) {
            Optional<TradingOrder> existing = orderLifecycleService.find(idempotencyKey);
            if (existing.isPresent() && existing.get().getStatus() != OrderStatus.PENDING_SUBMIT) {
                // A signal owns its first reserved order, including its original
                // size. Replays must not propose new parameters to that ledger row.
                record.setOrderSize(existing.get().getRequestedSize().stripTrailingZeros().toPlainString());
                applyOrder(record, existing.get(), true);
                reconcileReplay(record, existing.get());
                return;
            }
        }
        OrderSubmission submission = new OrderSubmission(
                idempotencyKey,
                clientOrderId,
                record.getDecisionId(),
                decision.getStrategyId(),
                properties.getInstId(),
                decision.getAction().name(),
                request.getSide(),
                request.getTdMode(),
                request.getOrdType(),
                request.getTgtCcy(),
                new BigDecimal(request.getSz())
        );

        OrderReservation reservation;
        try {
            reservation = maintenance == null ? orderLifecycleService.reserve(submission)
                    : maintenance.execute(record, () -> orderLifecycleService.reserve(submission));
        } catch (java.util.ConcurrentModificationException e) {
            record.setExecutionStatus("SKIPPED").setSkipReason(e.getMessage());
            return;
        }
        applyOrder(record, reservation.order(), !reservation.acquired());
        if (!reservation.acquired()) {
            reconcileReplay(record, reservation.order());
            return;
        }

        OrderActionResp actionResp;
        try {
            // Re-check runtime admission, distributed ownership and the persistent stop after
            // acquiring submission ownership. Neither failure may be treated as
            // an ambiguous external submit because placeOrder was not called.
            fundSafetyService.requireActive();
            leadershipService.requireLiveSubmissionLeadership();
            requireNoOutstandingOrder(idempotencyKey);
            if (decision.getAction() == TradingAction.SELL) {
                requireManagedSell(request);
            }
            actionResp = orderGateway.placeOrder(request);
        } catch (OutstandingOrderUnavailableException e) {
            TradingOrder rejected = orderLifecycleService.markSubmissionBlocked(
                    idempotencyKey, "OUTSTANDING_ORDER_UNAVAILABLE", e.getMessage()).order();
            applyOrder(record, rejected, false);
            record.setExecutionStatus("SKIPPED").setSkipReason(e.getMessage());
            return;
        } catch (ManagedPositionUnavailableException e) {
            TradingOrder rejected = orderLifecycleService.markSubmissionBlocked(
                    idempotencyKey, "MANAGED_POSITION_UNAVAILABLE", e.getMessage()).order();
            applyOrder(record, rejected, false);
            record.setExecutionStatus("SKIPPED").setSkipReason(e.getMessage());
            return;
        } catch (TradingLeadershipService.TradingLeadershipUnavailableException e) {
            TradingOrder rejected = orderLifecycleService.markSubmissionBlocked(
                    idempotencyKey,
                    "LEADERSHIP_UNAVAILABLE",
                    e.getMessage()
            ).order();
            applyOrder(record, rejected, false);
            record.setExecutionStatus("SKIPPED").setSkipReason(e.getMessage());
            return;
        } catch (FundSafetyService.TradingFundsHaltedException e) {
            TradingOrder rejected = orderLifecycleService.markSubmissionBlocked(
                    idempotencyKey,
                    "FUND_SAFETY_HALTED",
                    e.getMessage()
            ).order();
            applyOrder(record, rejected, false);
            record.setExecutionStatus("SKIPPED").setSkipReason(e.getMessage());
            return;
        } catch (OrderRejectedException e) {
            TradingOrder rejected = orderLifecycleService.markRejected(idempotencyKey, e.getMessage()).order();
            applyOrder(record, rejected, false);
            record.setExecutionStatus(OrderStatus.REJECTED.name()).setError(e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            TradingOrder unknown = orderLifecycleService.markSubmitUnknown(idempotencyKey, e.getMessage()).order();
            applyOrder(record, unknown, false);
            record.setExecutionStatus(OrderStatus.SUBMIT_UNKNOWN.name()).setError(e.getMessage());
            throw e;
        }

        TradingOrder accepted = orderLifecycleService.markAccepted(idempotencyKey, actionResp.getOrdId()).order();
        applyOrder(record, accepted, false);

        try {
            Optional<OrderInfoResp> orderInfo = orderGateway.queryOrder(actionResp.getOrdId(), actionResp.getClOrdId());
            if (orderInfo.isEmpty()) {
                record.setExecutionStatus("FILL_UNCONFIRMED");
                return;
            }
            applyExchangeOrder(record, orderInfo.get());
        } catch (RuntimeException e) {
            // The order is already accepted. A read-side failure must never turn
            // into another submit attempt; a replay reconciles by deterministic clOrdId.
            record.setExecutionStatus("FILL_UNCONFIRMED").setError(e.getMessage());
            log.warn("OKX order accepted but fill reconciliation failed: clOrdId={}, err={}",
                    actionResp.getClOrdId(), e.getMessage(), e);
        }
    }

    private BigDecimal managedQuantity() {
        BigDecimal quantity = stateRepository.getState().getTrackedBaseAmount();
        return quantity == null ? BigDecimal.ZERO : quantity.max(BigDecimal.ZERO);
    }

    private void requireManagedSell(PlaceOrderReq request) {
        try {
            if (new BigDecimal(request.getSz()).compareTo(managedQuantity()) > 0) {
                throw new ManagedPositionUnavailableException(
                        "SELL blocked: managed position changed before submission; refresh the decision");
            }
        } catch (ManagedPositionUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // No exchange submission occurred; a failed ownership read must not
            // be recorded as an ambiguous external submit.
            throw new ManagedPositionUnavailableException("SELL blocked: cannot verify the managed position");
        }
    }

    private void requireNoOutstandingOrder(String idempotencyKey) {
        try {
            // A new candle produces a new idempotency key. It must not create
            // another exposure while an earlier submit or fill is unresolved.
            if (orderLifecycleService.hasOtherUnresolvedOrder(properties.getInstId(), idempotencyKey)) {
                throw new OutstandingOrderUnavailableException(
                        "Live order blocked: reconcile outstanding orders before a new submission");
            }
        } catch (OutstandingOrderUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OutstandingOrderUnavailableException("Live order blocked: cannot verify outstanding orders");
        }
    }

    private static final class OutstandingOrderUnavailableException extends IllegalStateException {
        private OutstandingOrderUnavailableException(String message) {
            super(message);
        }
    }

    private static final class ManagedPositionUnavailableException extends IllegalStateException {
        private ManagedPositionUnavailableException(String message) {
            super(message);
        }
    }

    private void reconcileReplay(TradingDecisionRecord record, TradingOrder order) {
        if (order.getStatus().isTerminal()) {
            return;
        }
        try {
            Optional<OrderInfoResp> orderInfo = orderGateway.queryOrder(order.getExchangeOrderId(), order.getClientOrderId());
            if (orderInfo.isPresent()) {
                applyExchangeOrder(record, orderInfo.get());
            }
        } catch (RuntimeException e) {
            // Another worker may still be between SUBMITTING and ACCEPTED. The
            // duplicate caller never submits; it only reports the durable state.
            log.info("Idempotent replay reconciliation deferred: clOrdId={}, status={}, err={}",
                    order.getClientOrderId(), order.getStatus(), e.getMessage());
        }
    }

    private void applyExchangeOrder(
            TradingDecisionRecord record,
            OrderInfoResp exchangeOrder
    ) {
        TradingOrder localOrder = orderLifecycleService.find(record.getIdempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + record.getIdempotencyKey()));
        OrderSettlementResult settlement = orderSettlementService.applyExchangeSnapshot(localOrder, exchangeOrder);
        record.setExecutionStatus(settlement.executionStatus());
        applyOrder(record, settlement.order(), record.isIdempotentReplay());
    }

    private static void applyOrder(TradingDecisionRecord record, TradingOrder order, boolean replay) {
        record.setIdempotencyKey(order.getIdempotencyKey())
                .setClientOrderId(order.getClientOrderId())
                .setOrderId(order.getExchangeOrderId())
                .setOrderStatus(order.getStatus().name())
                .setOrderStatusVersion(order.getVersion())
                .setIdempotentReplay(replay)
                .setFilledBaseAmount(order.getFilledBaseAmount())
                .setAverageFillPrice(order.getAverageFillPrice())
                .setFee(order.getFee())
                .setFeeCcy(order.getFeeCcy());
        if (replay) {
            record.setExecutionStatus(order.getStatus().name());
        }
    }

    private static DerivativeOrder derivativeOrder(TradingAction action) {
        return switch (action) {
            case OPEN_LONG -> new DerivativeOrder("buy", "long", false);
            case CLOSE_LONG -> new DerivativeOrder("sell", "long", true);
            case OPEN_SHORT -> new DerivativeOrder("sell", "short", false);
            case CLOSE_SHORT -> new DerivativeOrder("buy", "short", true);
            default -> throw new IllegalArgumentException("Unsupported derivative action: " + action);
        };
    }

    private record DerivativeOrder(
            String side,
            String posSide,
            boolean reduceOnly
    ) {
    }

}
