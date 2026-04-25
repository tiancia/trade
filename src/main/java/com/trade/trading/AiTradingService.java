package com.trade.trading;

import com.trade.client.okx.OkxApi;
import com.trade.common.CommonResponse;
import com.trade.dto.OrderActionResp;
import com.trade.dto.OrderInfoResp;
import com.trade.dto.OrderQueryReq;
import com.trade.dto.PlaceOrderReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class AiTradingService {
    private static final Logger log = LoggerFactory.getLogger(AiTradingService.class);

    private final OkxApi okxApi;
    private final AiTextClient aiTextClient;
    private final AiTradingDecisionParser decisionParser;
    private final MarketContextCollector contextCollector;
    private final AiPromptBuilder promptBuilder;
    private final OrderSizingService orderSizingService;
    private final TradingStateRepository stateRepository;
    private final AiTradingProperties properties;
    private final ReentrantLock decisionLock = new ReentrantLock();

    public AiTradingService(
            OkxApi okxApi,
            AiTextClient aiTextClient,
            AiTradingDecisionParser decisionParser,
            MarketContextCollector contextCollector,
            AiPromptBuilder promptBuilder,
            OrderSizingService orderSizingService,
            TradingStateRepository stateRepository,
            AiTradingProperties properties
    ) {
        this.okxApi = okxApi;
        this.aiTextClient = aiTextClient;
        this.decisionParser = decisionParser;
        this.contextCollector = contextCollector;
        this.promptBuilder = promptBuilder;
        this.orderSizingService = orderSizingService;
        this.stateRepository = stateRepository;
        this.properties = properties;
    }

    public boolean runDecision(TradingTrigger trigger) {
        if (!properties.isEnabled()) {
            log.info("AI trading is disabled, skip trigger={}", trigger);
            return false;
        }
        if (!decisionLock.tryLock()) {
            log.info("AI decision is already running, skip trigger={}", trigger);
            return false;
        }

        try {
            TradingDecisionContext context = contextCollector.collect(trigger);
            log.info("AI decision parameters: {}", context.getAiParametersJson());

            String prompt = promptBuilder.buildPrompt(context.getAiParametersJson());
            String rawAiResponse = aiTextClient.generateJson(prompt);
            log.info("AI raw decision response: {}", rawAiResponse);

            AiTradingDecision decision = decisionParser.parse(rawAiResponse);
            log.info(
                    "AI parsed decision: action={}, reason={}, buyQuoteAmountUsdt={}, sellBaseAmountBtc={}",
                    decision.getAction(),
                    decision.getReason(),
                    decision.getBuyQuoteAmountUsdt(),
                    decision.getSellBaseAmountBtc()
            );

            executeDecision(decision, context);
            return true;
        } catch (Exception e) {
            log.error("AI trading decision failed, trigger={}", trigger, e);
            return false;
        } finally {
            decisionLock.unlock();
        }
    }

    private void executeDecision(AiTradingDecision decision, TradingDecisionContext context) {
        if (decision.getAction() == TradingAction.BUY) {
            executeBuy(decision, context);
        } else if (decision.getAction() == TradingAction.SELL) {
            executeSell(decision, context);
        } else {
            log.info("AI decision HOLD, no order placed. reason={}", decision.getReason());
        }
    }

    private void executeBuy(AiTradingDecision decision, TradingDecisionContext context) {
        OrderSizing sizing = orderSizingService.buySize(decision, context);
        if (!sizing.isExecutable()) {
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
                .setTag("ai-trade");

        log.info("Place BUY order request: {}", req);
        OrderActionResp actionResp = placeOrder(req);
        updateStateFromFilledOrder(actionResp, "buy");
    }

    private void executeSell(AiTradingDecision decision, TradingDecisionContext context) {
        OrderSizing sizing = orderSizingService.sellSize(decision, context);
        if (!sizing.isExecutable()) {
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
                .setTag("ai-trade");

        log.info("Place SELL order request: {}", req);
        OrderActionResp actionResp = placeOrder(req);
        updateStateFromFilledOrder(actionResp, "sell");
    }

    private OrderActionResp placeOrder(PlaceOrderReq req) {
        CommonResponse<OrderActionResp> response = okxApi.placeOrder(req);
        OrderActionResp actionResp = OkxResponses.requireFirst(response, "order action");
        if (actionResp.getSCode() != null && !"0".equals(actionResp.getSCode())) {
            throw new IllegalStateException(
                    "OKX order rejected, sCode=" + actionResp.getSCode() + ", sMsg=" + actionResp.getSMsg()
            );
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

    private void updateStateFromFilledOrder(OrderActionResp actionResp, String side) {
        Optional<OrderInfoResp> orderInfo = queryFilledOrder(actionResp);
        if (orderInfo.isEmpty()) {
            log.info("Order fill not confirmed, local trading state not updated. actionResp={}", actionResp);
            return;
        }

        OrderInfoResp order = orderInfo.get();
        BigDecimal filledBase = fillBaseAmount(order);
        BigDecimal averagePrice = fillAveragePrice(order);
        if (filledBase.signum() <= 0) {
            log.info("Filled order has zero fill size, local trading state not updated. order={}", order);
            return;
        }

        if ("buy".equals(side)) {
            if (averagePrice.signum() <= 0) {
                log.info("Filled BUY order has no average price, local trading state not updated. order={}", order);
                return;
            }
            stateRepository.recordBuy(filledBase, averagePrice);
            log.info("Local trading state updated after BUY: filledBase={}, avgPrice={}", filledBase, averagePrice);
        } else {
            stateRepository.recordSell(filledBase);
            log.info("Local trading state updated after SELL: filledBase={}", filledBase);
        }
    }

    private Optional<OrderInfoResp> queryFilledOrder(OrderActionResp actionResp) {
        String ordId = actionResp.getOrdId();
        String clOrdId = actionResp.getClOrdId();
        for (int i = 0; i < properties.getOrderFillQueryAttempts(); i++) {
            CommonResponse<OrderInfoResp> response = okxApi.getOrder(new OrderQueryReq()
                    .setInstId(properties.getInstId())
                    .setOrdId(ordId)
                    .setClOrdId(clOrdId));
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

    private static String clientOrderId(String side) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return ("ai" + side + suffix).substring(0, 32);
    }
}
