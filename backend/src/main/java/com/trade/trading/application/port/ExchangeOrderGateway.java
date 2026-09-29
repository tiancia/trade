package com.trade.trading.application.port;

import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.PlaceOrderReq;
import java.util.Optional;

/** Submission and read-back protocol; retrying a query never repeats a submission. */
public interface ExchangeOrderGateway {
    OrderActionResp placeOrder(PlaceOrderReq request);
    Optional<OrderInfoResp> queryOrder(String orderId, String clientOrderId);
    final class OrderRejectedException extends IllegalStateException {
        public OrderRejectedException(String message) { super(message); }
    }
}
