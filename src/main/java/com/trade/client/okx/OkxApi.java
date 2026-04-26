package com.trade.client.okx;

import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.AccountBalanceReq;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.CancelAllAfterReq;
import com.trade.client.okx.dto.CancelOrderReq;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.CandlesReq;
import com.trade.client.okx.dto.FillResp;
import com.trade.client.okx.dto.FillsReq;
import com.trade.client.okx.dto.InstrumentInfoReq;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.OrderActionResp;
import com.trade.client.okx.dto.OrderBookReq;
import com.trade.client.okx.dto.OrderBookResp;
import com.trade.client.okx.dto.OrderHistoryReq;
import com.trade.client.okx.dto.OrderInfoResp;
import com.trade.client.okx.dto.OrderQueryReq;
import com.trade.client.okx.dto.PendingOrdersReq;
import com.trade.client.okx.dto.PlaceOrderReq;
import com.trade.client.okx.dto.SystemTimeResp;
import com.trade.client.okx.dto.TickerReq;
import com.trade.client.okx.dto.TickerResp;
import com.trade.client.okx.ws.AccountChannelReq;
import com.trade.client.okx.ws.CandleChannelReq;
import com.trade.client.okx.ws.OrderBookChannelReq;
import com.trade.client.okx.ws.OrdersChannelReq;
import com.trade.client.okx.ws.TickerChannelReq;
import com.trade.client.okx.ws.OkxWsListener;
import com.trade.client.okx.ws.OkxWsSubscription;

public class OkxApi {

    private final OkxRestClient okxClient;
    private final OkxWebSocketClient okxWebSocketClient;

    /**
     * Creates the OKX API facade with the default REST and WebSocket clients.
     */
    public OkxApi(OkxRestClient okxClient) {
        this.okxClient = okxClient;
        this.okxWebSocketClient = new OkxWebSocketClient();
    }

    /**
     * Creates the OKX API facade with explicit clients, mainly for tests or custom transport setup.
     */
    public OkxApi(OkxRestClient okxClient, OkxWebSocketClient okxWebSocketClient) {
        this.okxClient = okxClient;
        this.okxWebSocketClient = okxWebSocketClient;
    }

    /**
     * Gets OKX server time. Useful for checking local clock drift before signed requests.
     */
    public OkxResponse<SystemTimeResp> getSystemTime() {
        return okxClient.get(
                OkxEndpoints.SYSTEM_TIME,
                null,
                false,
                SystemTimeResp.class
        );
    }

    /**
     * Gets instrument metadata such as min order size, lot size, tick size, and trading state.
     */
    public OkxResponse<InstrumentInfoResp> getInstrumentInfo(InstrumentInfoReq req) {
        return okxClient.get(
                OkxEndpoints.INSTRUMENT_INFO,
                req,
                true,
                InstrumentInfoResp.class
        );
    }

    /**
     * Gets account balances. Pass ccy to narrow the result to one or more currencies.
     */
    public OkxResponse<AccountBalanceResp> getAccountBalance(AccountBalanceReq req) {
        return okxClient.get(
                OkxEndpoints.ACCOUNT_BALANCE,
                req,
                true,
                AccountBalanceResp.class
        );
    }

    /**
     * Gets the latest ticker snapshot for one instrument, including best bid/ask and 24h stats.
     */
    public OkxResponse<TickerResp> getTicker(TickerReq req) {
        return okxClient.get(
                OkxEndpoints.MARKET_TICKER,
                req,
                false,
                TickerResp.class
        );
    }

    /**
     * Gets the current order book snapshot for one instrument.
     */
    public OkxResponse<OrderBookResp> getOrderBook(OrderBookReq req) {
        return okxClient.get(
                OkxEndpoints.MARKET_BOOKS,
                req,
                false,
                OrderBookResp.class
        );
    }

    /**
     * Gets recent candlesticks. OKX returns newest rows first.
     */
    public OkxResponse<CandleResp> getCandles(CandlesReq req) {
        return okxClient.get(
                OkxEndpoints.MARKET_CANDLES,
                req,
                false,
                CandleResp.class
        );
    }

    /**
     * Places an order. The response only confirms request acceptance; query order state afterward.
     */
    public OkxResponse<OrderActionResp> placeOrder(PlaceOrderReq req) {
        return okxClient.post(
                OkxEndpoints.TRADE_ORDER,
                req,
                true,
                OrderActionResp.class
        );
    }

    /**
     * Cancels one order by ordId or clOrdId.
     */
    public OkxResponse<OrderActionResp> cancelOrder(CancelOrderReq req) {
        return okxClient.post(
                OkxEndpoints.TRADE_CANCEL_ORDER,
                req,
                true,
                OrderActionResp.class
        );
    }

    /**
     * Gets one order's current details by ordId or clOrdId.
     */
    public OkxResponse<OrderInfoResp> getOrder(OrderQueryReq req) {
        return okxClient.get(
                OkxEndpoints.TRADE_ORDER,
                req,
                true,
                OrderInfoResp.class
        );
    }

    /**
     * Gets live, unfinished orders.
     */
    public OkxResponse<OrderInfoResp> getPendingOrders(PendingOrdersReq req) {
        return okxClient.get(
                OkxEndpoints.TRADE_PENDING_ORDERS,
                req,
                true,
                OrderInfoResp.class
        );
    }

    /**
     * Gets recent historical orders, including filled and canceled orders.
     */
    public OkxResponse<OrderInfoResp> getOrderHistory(OrderHistoryReq req) {
        return okxClient.get(
                OkxEndpoints.TRADE_ORDER_HISTORY,
                req,
                true,
                OrderInfoResp.class
        );
    }

    /**
     * Gets trade fills for reconciliation, fee calculation, and execution tracking.
     */
    public OkxResponse<FillResp> getFills(FillsReq req) {
        return okxClient.get(
                OkxEndpoints.TRADE_FILLS,
                req,
                true,
                FillResp.class
        );
    }

    /**
     * Arms or cancels OKX's dead-man switch that cancels all orders after the timeout expires.
     */
    public OkxResponse<OrderActionResp> cancelAllAfter(CancelAllAfterReq req) {
        return okxClient.post(
                OkxEndpoints.TRADE_CANCEL_ALL_AFTER,
                req,
                true,
                OrderActionResp.class
        );
    }

    /**
     * Subscribes to real-time ticker updates for one instrument.
     */
    public OkxWsSubscription subscribeTicker(
            TickerChannelReq req,
            OkxWsListener<TickerResp> listener
    ) {
        return okxWebSocketClient.subscribePublic(req.toArg(), TickerResp.class, listener);
    }

    /**
     * Subscribes to real-time order book updates for one instrument.
     */
    public OkxWsSubscription subscribeOrderBook(
            OrderBookChannelReq req,
            OkxWsListener<OrderBookResp> listener
    ) {
        return okxWebSocketClient.subscribePublic(req.toArg(), OrderBookResp.class, listener);
    }

    /**
     * Subscribes to real-time candlestick updates for one instrument and bar size.
     */
    public OkxWsSubscription subscribeCandles(
            CandleChannelReq req,
            OkxWsListener<CandleResp> listener
    ) {
        return okxWebSocketClient.subscribePublic(req.toArg(), CandleResp.class, listener);
    }

    /**
     * Subscribes to private account balance updates.
     */
    public OkxWsSubscription subscribeAccount(
            AccountChannelReq req,
            OkxWsListener<AccountBalanceResp> listener
    ) {
        return okxWebSocketClient.subscribePrivate(req.toArg(), AccountBalanceResp.class, listener);
    }

    /**
     * Subscribes to private order updates. Use this to confirm fills, cancellations, and state changes.
     */
    public OkxWsSubscription subscribeOrders(
            OrdersChannelReq req,
            OkxWsListener<OrderInfoResp> listener
    ) {
        return okxWebSocketClient.subscribePrivate(req.toArg(), OrderInfoResp.class, listener);
    }
}
