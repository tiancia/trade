package com.trade.client.okx;

import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.AccountBalanceReq;
import com.trade.client.okx.dto.CancelOrderReq;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.client.okx.dto.FillsReq;
import com.trade.client.okx.dto.OrderBookReq;
import com.trade.client.okx.dto.OrderHistoryReq;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PendingOrdersReq;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.client.okx.dto.TickerReq;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OkxApiMethodTest {

    @Test
    void routesReadOnlyMarketMethodsWithoutAuth() {
        FakeOkxClient client = new FakeOkxClient();
        OkxApi api = new OkxApi(client);

        api.getTicker(new TickerReq().setInstId("BTC-USDT"));
        assertCall(client, "GET", OkxEndpoints.MARKET_TICKER, false);

        api.getOrderBook(new OrderBookReq().setInstId("BTC-USDT").setSz("5"));
        assertCall(client, "GET", OkxEndpoints.MARKET_BOOKS, false);

        api.getCandles(new CandlesReq().setInstId("BTC-USDT").setBar("1m"));
        assertCall(client, "GET", OkxEndpoints.MARKET_CANDLES, false);
    }

    @Test
    void routesPrivateAccountAndTradeMethodsWithAuth() {
        FakeOkxClient client = new FakeOkxClient();
        OkxApi api = new OkxApi(client);

        api.getAccountBalance(new AccountBalanceReq().setCcy("USDT"));
        assertCall(client, "GET", OkxEndpoints.ACCOUNT_BALANCE, true);

        api.placeOrder(new PlaceOrderReq().setInstId("BTC-USDT"));
        assertCall(client, "POST", OkxEndpoints.TRADE_ORDER, true);

        api.cancelOrder(new CancelOrderReq().setInstId("BTC-USDT").setOrdId("1"));
        assertCall(client, "POST", OkxEndpoints.TRADE_CANCEL_ORDER, true);

        api.getOrder(new OrderQueryReq().setInstId("BTC-USDT").setOrdId("1"));
        assertCall(client, "GET", OkxEndpoints.TRADE_ORDER, true);

        api.getPendingOrders(new PendingOrdersReq().setInstType("SPOT"));
        assertCall(client, "GET", OkxEndpoints.TRADE_PENDING_ORDERS, true);

        api.getOrderHistory(new OrderHistoryReq().setInstType("SPOT"));
        assertCall(client, "GET", OkxEndpoints.TRADE_ORDER_HISTORY, true);

        api.getFills(new FillsReq().setInstType("SPOT"));
        assertCall(client, "GET", OkxEndpoints.TRADE_FILLS, true);
    }

    private void assertCall(FakeOkxClient client, String method, String path, boolean needAuth) {
        assertEquals(method, client.method);
        assertEquals(path, client.path);
        assertEquals(needAuth, client.needAuth);
    }

    private static class FakeOkxClient implements OkxRestClient {
        private String method;
        private String path;
        private boolean needAuth;

        @Override
        public <T> OkxResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass) {
            this.method = "GET";
            this.path = path;
            this.needAuth = needAuth;
            return OkxResponse.success(List.of());
        }

        @Override
        public <T> OkxResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass) {
            this.method = "POST";
            this.path = path;
            this.needAuth = needAuth;
            return OkxResponse.success(List.of());
        }
    }
}
