package com.trade.client.okx;

import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.AccountBalanceReq;
import com.trade.client.okx.dto.CancelOrderReq;
import com.trade.client.okx.dto.CancelAllAfterReq;
import com.trade.client.okx.dto.CancelAllAfterResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.client.okx.dto.FillsReq;
import com.trade.client.okx.dto.InstrumentInfoReq;
import com.trade.client.okx.dto.OrderBookReq;
import com.trade.client.okx.dto.OrderHistoryReq;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PendingOrdersReq;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.client.okx.dto.TickerReq;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.CandleChannelReq;
import com.trade.client.okx.ws.TickerChannelReq;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsArg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OkxApiMethodTest {

    @Test
    void cancelAllAfterDeserializesTheTimerAcknowledgement() {
        OkxRestClient client = mock(OkxRestClient.class);
        OkxApi api = new OkxApi(client);
        CancelAllAfterReq request = new CancelAllAfterReq().setTimeOut("0");

        api.cancelAllAfter(request);

        verify(client).post(eq(OkxEndpoints.TRADE_CANCEL_ALL_AFTER), same(request), eq(true), eq(CancelAllAfterResp.class));
    }

    @Test
    void routesCandlesToBusinessWhileTickersRemainPublic() {
        OkxWebSocketClient websocket = mock(OkxWebSocketClient.class);
        OkxApi api = new OkxApi(new FakeOkxClient(), websocket);
        OkxWsListener<CandleResp> candleListener = new OkxWsListener<>() {};
        OkxWsListener<TickerResp> tickerListener = new OkxWsListener<>() {};

        api.subscribeCandles(new CandleChannelReq().setInstId("BTC-USDT"), candleListener);
        api.subscribeTicker(new TickerChannelReq().setInstId("BTC-USDT"), tickerListener);

        verify(websocket).subscribeBusiness(any(OkxWsArg.class), eq(CandleResp.class), same(candleListener));
        verify(websocket).subscribePublic(any(), eq(TickerResp.class), same(tickerListener));
    }

    @Test
    void routesAllCandlePeriodsThroughOneBusinessSubscription() {
        OkxWebSocketClient websocket = mock(OkxWebSocketClient.class);
        OkxApi api = new OkxApi(new FakeOkxClient(), websocket);
        OkxWsListener<CandleResp> listener = new OkxWsListener<>() {};
        List<CandleChannelReq> requests = List.of(
                new CandleChannelReq().setInstId("BTC-USDT").setChannel("candle1m"),
                new CandleChannelReq().setInstId("BTC-USDT").setChannel("candle5m"));

        api.subscribeCandles(requests, listener);

        verify(websocket).subscribeBusiness(org.mockito.ArgumentMatchers.<List<OkxWsArg>>argThat(args ->
                args.size() == 2 && "candle1m".equals(args.getFirst().getChannel())
                        && "candle5m".equals(args.getLast().getChannel())), eq(CandleResp.class), same(listener));
    }

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

        api.getHistoryCandles(new CandlesReq().setInstId("BTC-USDT").setBar("1m"));
        assertCall(client, "GET", OkxEndpoints.MARKET_HISTORY_CANDLES, false);

        api.getPublicInstrumentInfo(new InstrumentInfoReq().setInstType("SPOT").setInstId("BTC-USDT"));
        assertCall(client, "GET", OkxEndpoints.PUBLIC_INSTRUMENT_INFO, false);
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
