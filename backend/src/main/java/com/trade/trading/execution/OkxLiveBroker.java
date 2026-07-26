package com.trade.trading.execution;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.OrderSizing;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.order.OrderIdempotencyKeyFactory;
import com.trade.trading.order.OrderLifecycleService;
import com.trade.trading.order.OrderReservation;
import com.trade.trading.order.OrderSettlementResult;
import com.trade.trading.order.OrderSettlementService;
import com.trade.trading.order.OrderStatus;
import com.trade.trading.order.OrderSubmission;
import com.trade.trading.order.TradingOrder;
import com.trade.trading.risk.RiskAssessment;
import com.trade.trading.risk.RiskControlService;
import com.trade.trading.risk.FundSafetyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

@Component
public class OkxLiveBroker implements TradingBroker {
    private static final Logger log = LoggerFactory.getLogger(OkxLiveBroker.class);

    private final OkxApi okxApi;
    private final OrderSizingService orderSizingService;
    private final TradingProperties properties;
    private final RiskControlService riskControlService;
    private final FundSafetyService fundSafetyService;
    private final OrderLifecycleService orderLifecycleService;
    private final OrderSettlementService orderSettlementService;
    private final OrderIdempotencyKeyFactory idempotencyKeyFactory;

    public OkxLiveBroker(
            OkxApi okxApi,
            OrderSizingService orderSizingService,
            TradingProperties properties,
            RiskControlService riskControlService,
            FundSafetyService fundSafetyService,
            OrderLifecycleService orderLifecycleService,
            OrderSettlementService orderSettlementService,
            OrderIdempotencyKeyFactory idempotencyKeyFactory
    ) {
        this.okxApi = okxApi;
        this.orderSizingService = orderSizingService;
        this.properties = properties;
        this.riskControlService = riskControlService;
        this.fundSafetyService = fundSafetyService;
        this.orderLifecycleService = orderLifecycleService;
        this.orderSettlementService = orderSettlementService;
        this.idempotencyKeyFactory = idempotencyKeyFactory;
    }

    @Override
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
            fundSafetyService.requireActive();
        } catch (FundSafetyService.TradingFundsHaltedException e) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(e.getMessage());
            return;
        }

        RiskAssessment riskAssessment = riskControlService.evaluate(decision, context);
        if (!riskAssessment.isAllowed()) {
            fundSafetyService.haltForHardRisk(riskAssessment);
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
        if (!properties.isSpotInstrument()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason("BUY skipped: use OPEN_LONG for non-spot instruments");
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
        if (properties.isSpotInstrument()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason("Derivative action skipped: current instrument type is SPOT");
            return;
        }
        if ((decision.getAction() == TradingAction.OPEN_SHORT || decision.getAction() == TradingAction.CLOSE_SHORT)
                && !properties.isShortEnabled()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(decision.getAction() + " skipped: short trading is disabled by strategy.allowShort");
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
        if (!properties.isSpotInstrument()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason("SELL skipped: use CLOSE_LONG or OPEN_SHORT for non-spot instruments");
            return;
        }
        OrderSizing sizing = orderSizingService.sellSize(decision, context);
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

        OrderReservation reservation = orderLifecycleService.reserve(submission);
        applyOrder(record, reservation.order(), !reservation.acquired());
        if (!reservation.acquired()) {
            reconcileReplay(record, reservation.order());
            return;
        }

        OrderActionResp actionResp;
        try {
            // Re-read the persistent stop after acquiring submission ownership;
            // this narrows the race with an operator or automatic fund halt.
            fundSafetyService.requireActive();
            actionResp = placeOrder(request);
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
            Optional<OrderInfoResp> orderInfo = queryOrder(actionResp.getOrdId(), actionResp.getClOrdId());
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

    private void reconcileReplay(TradingDecisionRecord record, TradingOrder order) {
        if (order.getStatus().isTerminal()) {
            return;
        }
        try {
            Optional<OrderInfoResp> orderInfo = queryOrder(order.getExchangeOrderId(), order.getClientOrderId());
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

    private OrderActionResp placeOrder(PlaceOrderReq req) {
        OkxResponse<OrderActionResp> response = okxApi.placeOrder(req);
        OrderActionResp actionResp = OkxResponses.first(response)
                .orElseThrow(() -> new OrderRejectedException(OkxResponses.failureMessage(response, "order action")));
        if (!OkxResponses.isOk(response) || (actionResp.getSCode() != null && !"0".equals(actionResp.getSCode()))) {
            throw new OrderRejectedException(orderRejectedMessage(response, actionResp));
        }
        log.info("OKX order accepted: ordId={}, clOrdId={}", actionResp.getOrdId(), actionResp.getClOrdId());
        return actionResp;
    }

    private static String orderRejectedMessage(OkxResponse<OrderActionResp> response, OrderActionResp actionResp) {
        return "OKX order rejected, code=" + (response == null ? null : response.getCode())
                + ", msg=" + (response == null ? null : response.getMsg())
                + ", sCode=" + (actionResp == null ? null : actionResp.getSCode())
                + ", sMsg=" + (actionResp == null ? null : actionResp.getSMsg())
                + ", ordId=" + (actionResp == null ? null : actionResp.getOrdId())
                + ", clOrdId=" + (actionResp == null ? null : actionResp.getClOrdId());
    }

    private Optional<OrderInfoResp> queryOrder(String orderId, String clientOrderId) {
        OrderInfoResp lastObserved = null;
        for (int i = 0; i < properties.getOrderFillQueryAttempts(); i++) {
            OkxResponse<OrderInfoResp> response = okxApi.getOrder(new OrderQueryReq()
                    .setInstId(properties.getInstId())
                    .setOrdId(orderId)
                    .setClOrdId(clientOrderId));
            OkxResponses.requireOk(response, "order query");
            Optional<OrderInfoResp> order = OkxResponses.first(response);
            if (order.isPresent()) {
                lastObserved = order.get();
                String state = lastObserved.getState();
                if ("filled".equalsIgnoreCase(state)
                        || "canceled".equalsIgnoreCase(state)
                        || "mmp_canceled".equalsIgnoreCase(state)) {
                    return Optional.of(lastObserved);
                }
            }
            if (i + 1 < properties.getOrderFillQueryAttempts()) {
                sleepBeforeRetry();
            }
        }
        return Optional.ofNullable(lastObserved);
    }

    private void sleepBeforeRetry() {
        if (properties.getOrderFillQueryDelayMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(properties.getOrderFillQueryDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private static final class OrderRejectedException extends IllegalStateException {
        private OrderRejectedException(String message) {
            super(message);
        }
    }
}
