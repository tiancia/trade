package com.trade.trading.execution;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.OrderSizing;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.persistence.TradingStateRepository;
import com.trade.trading.support.TradingMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

@Component
public class TradingOrderExecutor {
    private static final Logger log = LoggerFactory.getLogger(TradingOrderExecutor.class);

    private final OkxApi okxApi;
    private final OrderSizingService orderSizingService;
    private final TradingStateRepository stateRepository;
    private final TradingProperties properties;

    public TradingOrderExecutor(
            OkxApi okxApi,
            OrderSizingService orderSizingService,
            TradingStateRepository stateRepository,
            TradingProperties properties
    ) {
        this.okxApi = okxApi;
        this.orderSizingService = orderSizingService;
        this.stateRepository = stateRepository;
        this.properties = properties;
    }

    public void execute(
            AiTradingDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        if (decision.getAction() == TradingAction.BUY) {
            executeBuy(decision, context, decisionRecord);
        } else if (decision.getAction() == TradingAction.SELL) {
            executeSell(decision, context, decisionRecord);
        } else {
            decisionRecord.setExecutionStatus("HELD");
            log.info("AI decision HOLD, no order placed. reason={}", decision.getReason());
        }
    }

    private void executeBuy(
            AiTradingDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        OrderSizing sizing = orderSizingService.buySize(decision, context);
        if (!sizing.isExecutable()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(sizing.getSkipReason());
            log.info("{}", sizing.getSkipReason());
            return;
        }

        PlaceOrderReq req = new PlaceOrderReq()
                .setInstId(properties.getInstId())
                .setTdMode(properties.getTdMode())
                .setSide("buy")
                .setOrdType("market")
                .setTgtCcy("quote_ccy")
                .setSz(sizing.getSize())
                .setClOrdId(clientOrderId("buy"))
                .setTag("aiTrade");
        decisionRecord.setOrderSize(sizing.getSize());

        log.info("Place BUY order request: {}", req);
        OrderActionResp actionResp = placeOrder(req);
        decisionRecord.setOrderId(actionResp.getOrdId())
                .setClientOrderId(actionResp.getClOrdId());
        Optional<FillSummary> fillSummary = updateStateFromFilledOrder(actionResp, "buy");
        applyFillSummary(decisionRecord, fillSummary);
    }

    private void executeSell(
            AiTradingDecision decision,
            TradingDecisionContext context,
            TradingDecisionRecord decisionRecord
    ) {
        OrderSizing sizing = orderSizingService.sellSize(decision, context);
        if (!sizing.isExecutable()) {
            decisionRecord.setExecutionStatus("SKIPPED")
                    .setSkipReason(sizing.getSkipReason());
            log.info("{}", sizing.getSkipReason());
            return;
        }

        PlaceOrderReq req = new PlaceOrderReq()
                .setInstId(properties.getInstId())
                .setTdMode(properties.getTdMode())
                .setSide("sell")
                .setOrdType("market")
                .setTgtCcy("base_ccy")
                .setSz(sizing.getSize())
                .setClOrdId(clientOrderId("sell"))
                .setTag("aiTrade");
        decisionRecord.setOrderSize(sizing.getSize());

        log.info("Place SELL order request: {}", req);
        OrderActionResp actionResp = placeOrder(req);
        decisionRecord.setOrderId(actionResp.getOrdId())
                .setClientOrderId(actionResp.getClOrdId());
        Optional<FillSummary> fillSummary = updateStateFromFilledOrder(actionResp, "sell");
        applyFillSummary(decisionRecord, fillSummary);
    }

    private OrderActionResp placeOrder(PlaceOrderReq req) {
        OkxResponse<OrderActionResp> response = okxApi.placeOrder(req);
        OrderActionResp actionResp = OkxResponses.first(response)
                .orElseThrow(() -> new IllegalStateException(OkxResponses.failureMessage(response, "order action")));
        if (!OkxResponses.isOk(response) || (actionResp.getSCode() != null && !"0".equals(actionResp.getSCode()))) {
            throw new IllegalStateException(orderRejectedMessage(response, actionResp));
        }
        log.info(
                "OKX order accepted: ordId={}, clOrdId={}, sCode={}, sMsg={}",
                actionResp.getOrdId(),
                actionResp.getClOrdId(),
                actionResp.getSCode(),
                actionResp.getSMsg()
        );
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

    private Optional<FillSummary> updateStateFromFilledOrder(OrderActionResp actionResp, String side) {
        Optional<OrderInfoResp> orderInfo = queryFilledOrder(actionResp);
        if (orderInfo.isEmpty()) {
            log.info("Order fill not confirmed, local trading state not updated. actionResp={}", actionResp);
            return Optional.empty();
        }

        OrderInfoResp order = orderInfo.get();
        BigDecimal filledBase = fillBaseAmount(order);
        BigDecimal averagePrice = fillAveragePrice(order);
        BigDecimal fee = TradingMath.decimal(order.getFee());
        String feeCcy = order.getFeeCcy();
        if (filledBase.signum() <= 0) {
            log.info("Filled order has zero fill size, local trading state not updated. order={}", order);
            return Optional.empty();
        }

        if ("buy".equals(side)) {
            if (averagePrice.signum() <= 0) {
                log.info("Filled BUY order has no average price, local trading state not updated. order={}", order);
                return Optional.empty();
            }
            BigDecimal netBase = buyBaseAfterFee(order, filledBase);
            if (netBase.signum() <= 0) {
                log.info("Filled BUY order has no net base after fee, local trading state not updated. order={}", order);
                return Optional.empty();
            }
            BigDecimal averageCostAfterFee = buyAverageCostAfterFee(order, filledBase, averagePrice, netBase);
            stateRepository.recordBuy(netBase, averageCostAfterFee);
            log.info(
                    "Local trading state updated after BUY: filledBase={}, netBase={}, avgPrice={}, avgCostAfterFee={}, fee={}, feeCcy={}",
                    filledBase,
                    netBase,
                    averagePrice,
                    averageCostAfterFee,
                    fee,
                    feeCcy
            );
        } else {
            BigDecimal baseReduction = sellBaseReductionAfterFee(order, filledBase);
            stateRepository.recordSell(baseReduction);
            log.info(
                    "Local trading state updated after SELL: filledBase={}, baseReduction={}, fee={}, feeCcy={}",
                    filledBase,
                    baseReduction,
                    fee,
                    feeCcy
            );
        }
        return Optional.of(new FillSummary(filledBase, averagePrice, fee, feeCcy));
    }

    private Optional<OrderInfoResp> queryFilledOrder(OrderActionResp actionResp) {
        String ordId = actionResp.getOrdId();
        String clOrdId = actionResp.getClOrdId();
        for (int i = 0; i < properties.getOrderFillQueryAttempts(); i++) {
            OkxResponse<OrderInfoResp> response = okxApi.getOrder(new OrderQueryReq()
                    .setInstId(properties.getInstId())
                    .setOrdId(ordId)
                    .setClOrdId(clOrdId));
            OkxResponses.requireOk(response, "order query");
            Optional<OrderInfoResp> order = OkxResponses.first(response);
            if (order.isPresent() && fillBaseAmount(order.get()).signum() > 0) {
                return order;
            }
            sleepBeforeRetry();
        }
        return Optional.empty();
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

    private static BigDecimal fillBaseAmount(OrderInfoResp order) {
        BigDecimal accFill = TradingMath.decimal(order.getAccFillSz());
        if (accFill.signum() > 0) {
            return accFill;
        }
        return TradingMath.decimal(order.getFillSz());
    }

    private static BigDecimal fillAveragePrice(OrderInfoResp order) {
        BigDecimal avg = TradingMath.decimal(order.getAvgPx());
        if (avg.signum() > 0) {
            return avg;
        }
        return TradingMath.decimal(order.getFillPx());
    }

    private BigDecimal buyBaseAfterFee(OrderInfoResp order, BigDecimal filledBase) {
        if (sameCurrency(order.getFeeCcy(), properties.getBaseCcy())) {
            return filledBase.subtract(TradingMath.decimal(order.getFee()).abs());
        }
        return filledBase;
    }

    private BigDecimal buyAverageCostAfterFee(
            OrderInfoResp order,
            BigDecimal filledBase,
            BigDecimal averagePrice,
            BigDecimal netBase
    ) {
        BigDecimal quoteCost = filledBase.multiply(averagePrice);
        if (sameCurrency(order.getFeeCcy(), properties.getQuoteCcy())) {
            quoteCost = quoteCost.add(TradingMath.decimal(order.getFee()).abs());
        }
        return quoteCost.divide(netBase, 18, RoundingMode.HALF_UP);
    }

    private BigDecimal sellBaseReductionAfterFee(OrderInfoResp order, BigDecimal filledBase) {
        if (sameCurrency(order.getFeeCcy(), properties.getBaseCcy())) {
            return filledBase.add(TradingMath.decimal(order.getFee()).abs());
        }
        return filledBase;
    }

    private static boolean sameCurrency(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static void applyFillSummary(
            TradingDecisionRecord decisionRecord,
            Optional<FillSummary> fillSummary
    ) {
        if (fillSummary.isEmpty()) {
            decisionRecord.setExecutionStatus("FILL_UNCONFIRMED");
            return;
        }

        FillSummary summary = fillSummary.get();
        decisionRecord.setExecutionStatus("FILLED")
                .setFilledBaseAmount(summary.filledBaseAmount())
                .setAverageFillPrice(summary.averagePrice())
                .setFee(summary.fee())
                .setFeeCcy(summary.feeCcy());
    }

    private static String clientOrderId(String side) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return ("ai" + side + suffix).substring(0, 32);
    }

    private record FillSummary(
            BigDecimal filledBaseAmount,
            BigDecimal averagePrice,
            BigDecimal fee,
            String feeCcy
    ) {
    }
}
