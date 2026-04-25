package com.trade.client.okx;

import com.trade.common.CommonResponse;
import com.trade.constdef.RequestPath;
import com.trade.dto.AccountBalanceReq;
import com.trade.dto.CancelOrderReq;
import com.trade.dto.CandlesReq;
import com.trade.dto.FillsReq;
import com.trade.dto.OrderBookReq;
import com.trade.dto.OrderHistoryReq;
import com.trade.dto.OrderQueryReq;
import com.trade.dto.PendingOrdersReq;
import com.trade.dto.PlaceOrderReq;
import com.trade.dto.TickerReq;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxApiMethodTest {

    @Test
    void routesReadOnlyMarketMethodsWithoutAuth() {
        FakeOkxClient client = new FakeOkxClient();
        OkxApi api = new OkxApi(client);

        api.getTicker(new TickerReq().setInstId("BTC-USDT"));
        assertCall(client, "GET", RequestPath.MARKET_TICKER, false);

        api.getOrderBook(new OrderBookReq().setInstId("BTC-USDT").setSz("5"));
        assertCall(client, "GET", RequestPath.MARKET_BOOKS, false);

        api.getCandles(new CandlesReq().setInstId("BTC-USDT").setBar("1m"));
        assertCall(client, "GET", RequestPath.MARKET_CANDLES, false);
    }

    @Test
    void routesPrivateAccountAndTradeMethodsWithAuth() {
        FakeOkxClient client = new FakeOkxClient();
        OkxApi api = new OkxApi(client);

        api.getAccountBalance(new AccountBalanceReq().setCcy("USDT"));
        assertCall(client, "GET", RequestPath.ACCOUNT_BALANCE, true);

        api.placeOrder(new PlaceOrderReq().setInstId("BTC-USDT"));
        assertCall(client, "POST", RequestPath.TRADE_ORDER, true);

        api.cancelOrder(new CancelOrderReq().setInstId("BTC-USDT").setOrdId("1"));
        assertCall(client, "POST", RequestPath.TRADE_CANCEL_ORDER, true);

        api.getOrder(new OrderQueryReq().setInstId("BTC-USDT").setOrdId("1"));
        assertCall(client, "GET", RequestPath.TRADE_ORDER, true);

        api.getPendingOrders(new PendingOrdersReq().setInstType("SPOT"));
        assertCall(client, "GET", RequestPath.TRADE_PENDING_ORDERS, true);

        api.getOrderHistory(new OrderHistoryReq().setInstType("SPOT"));
        assertCall(client, "GET", RequestPath.TRADE_ORDER_HISTORY, true);

        api.getFills(new FillsReq().setInstType("SPOT"));
        assertCall(client, "GET", RequestPath.TRADE_FILLS, true);
    }

    private void assertCall(FakeOkxClient client, String method, String path, boolean needAuth) {
        assertEquals(method, client.method);
        assertEquals(path, client.path);
        assertEquals(needAuth, client.needAuth);
    }

    private static class FakeOkxClient extends OkxClient {
        private String method;
        private String path;
        private boolean needAuth;

        @Override
        public <T> CommonResponse<T> get(String path, Object req, boolean needAuth, Class<T> dataClass) {
            this.method = "GET";
            this.path = path;
            this.needAuth = needAuth;
            return CommonResponse.success(List.of());
        }

        @Override
        public <T> CommonResponse<T> post(String path, Object req, boolean needAuth, Class<T> dataClass) {
            this.method = "POST";
            this.path = path;
            this.needAuth = needAuth;
            return CommonResponse.success(List.of());
        }
    }
}
