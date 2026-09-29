package com.trade.trading.infrastructure.broker;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.OkxResponses;
import com.trade.client.okx.dto.*;
import com.trade.trading.application.port.ExchangeOrderGateway;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Optional;

@Component
public class OkxOrderGateway implements ExchangeOrderGateway {
    private static final Logger log = LoggerFactory.getLogger(OkxOrderGateway.class);
    private final OkxApi okxApi;
    private final TradingProperties properties;
    public OkxOrderGateway(OkxApi okxApi, TradingProperties properties) {
        this.okxApi = okxApi; this.properties = properties;
    }

    public OrderActionResp placeOrder(PlaceOrderReq req) {
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

    public Optional<OrderInfoResp> queryOrder(String orderId, String clientOrderId) {
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
}
