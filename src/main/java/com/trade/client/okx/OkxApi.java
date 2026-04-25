package com.trade.client.okx;

import com.trade.common.CommonResponse;
import com.trade.constdef.RequestPath;
import com.trade.dto.AccountBalanceReq;
import com.trade.dto.AccountBalanceResp;
import com.trade.dto.CancelAllAfterReq;
import com.trade.dto.CancelOrderReq;
import com.trade.dto.CandleResp;
import com.trade.dto.CandlesReq;
import com.trade.dto.FillResp;
import com.trade.dto.FillsReq;
import com.trade.dto.InstrumentInfoReq;
import com.trade.dto.InstrumentInfoResp;
import com.trade.dto.OrderActionResp;
import com.trade.dto.OrderBookReq;
import com.trade.dto.OrderBookResp;
import com.trade.dto.OrderHistoryReq;
import com.trade.dto.OrderInfoResp;
import com.trade.dto.OrderQueryReq;
import com.trade.dto.PendingOrdersReq;
import com.trade.dto.PlaceOrderReq;
import com.trade.dto.SystemTimeResp;
import com.trade.dto.TickerReq;
import com.trade.dto.TickerResp;
import com.trade.dto.ws.AccountChannelReq;
import com.trade.dto.ws.CandleChannelReq;
import com.trade.dto.ws.OrderBookChannelReq;
import com.trade.dto.ws.OrdersChannelReq;
import com.trade.dto.ws.TickerChannelReq;
import com.trade.dto.ws.OkxWsListener;
import com.trade.dto.ws.OkxWsSubscription;

public class OkxApi {

    private final OkxClient okxClient;
    private final OkxWebSocketClient okxWebSocketClient;

    /**
     * Creates the OKX API facade with the default REST and WebSocket clients.
     */
    public OkxApi(OkxClient okxClient) {
        this.okxClient = okxClient;
        this.okxWebSocketClient = new OkxWebSocketClient();
    }

    /**
     * Creates the OKX API facade with explicit clients, mainly for tests or custom transport setup.
     */
    public OkxApi(OkxClient okxClient, OkxWebSocketClient okxWebSocketClient) {
        this.okxClient = okxClient;
        this.okxWebSocketClient = okxWebSocketClient;
    }

    /**
     * Gets OKX server time. Useful for checking local clock drift before signed requests.
     */
    public CommonResponse<SystemTimeResp> getSystemTime() {
        return okxClient.get(
                RequestPath.SYSTEM_TIME,
                null,
                false,
                SystemTimeResp.class
        );
    }

    /**
     * Gets instrument metadata such as min order size, lot size, tick size, and trading state.
     */
    public CommonResponse<InstrumentInfoResp> getInstrumentInfo(InstrumentInfoReq req) {
        return okxClient.get(
                RequestPath.INSTRUMENT_INFO,
                req,
                true,
                InstrumentInfoResp.class
        );
    }

    /**
     * Gets account balances. Pass ccy to narrow the result to one or more currencies.
     */
    public CommonResponse<AccountBalanceResp> getAccountBalance(AccountBalanceReq req) {
        return okxClient.get(
                RequestPath.ACCOUNT_BALANCE,
                req,
                true,
                AccountBalanceResp.class
        );
    }

    /**
     * Gets the latest ticker snapshot for one instrument, including best bid/ask and 24h stats.
     */
    public CommonResponse<TickerResp> getTicker(TickerReq req) {
        return okxClient.get(
                RequestPath.MARKET_TICKER,
                req,
                false,
                TickerResp.class
        );
    }

    /**
     * Gets the current order book snapshot for one instrument.
     */
    public CommonResponse<OrderBookResp> getOrderBook(OrderBookReq req) {
        return okxClient.get(
                RequestPath.MARKET_BOOKS,
                req,
                false,
                OrderBookResp.class
        );
    }

    /**
     * Gets recent candlesticks. OKX returns newest rows first.
     */
    public CommonResponse<CandleResp> getCandles(CandlesReq req) {
        return okxClient.get(
                RequestPath.MARKET_CANDLES,
                req,
                false,
                CandleResp.class
        );
    }

    /**
     * Gets historical candlesticks for backfill or strategy warm-up.
     */
    public CommonResponse<CandleResp> getHistoryCandles(CandlesReq req) {
        return okxClient.get(
                RequestPath.MARKET_HISTORY_CANDLES,
                req,
                false,
                CandleResp.class
        );
    }

    /**
     * Places an order. The response only confirms request acceptance; query order state afterward.
     */
    public CommonResponse<OrderActionResp> placeOrder(PlaceOrderReq req) {
        return okxClient.post(
                RequestPath.TRADE_ORDER,
                req,
                true,
                OrderActionResp.class
        );
    }

    /**
     * Cancels one order by ordId or clOrdId.
     */
    public CommonResponse<OrderActionResp> cancelOrder(CancelOrderReq req) {
        return okxClient.post(
                RequestPath.TRADE_CANCEL_ORDER,
                req,
                true,
                OrderActionResp.class
        );
    }

    /**
     * Gets one order's current details by ordId or clOrdId.
     */
    public CommonResponse<OrderInfoResp> getOrder(OrderQueryReq req) {
        return okxClient.get(
                RequestPath.TRADE_ORDER,
                req,
                true,
                OrderInfoResp.class
        );
    }

    /**
     * Gets live, unfinished orders.
     */
    public CommonResponse<OrderInfoResp> getPendingOrders(PendingOrdersReq req) {
        return okxClient.get(
                RequestPath.TRADE_PENDING_ORDERS,
                req,
                true,
                OrderInfoResp.class
        );
    }

    /**
     * Gets recent historical orders, including filled and canceled orders.
     */
    public CommonResponse<OrderInfoResp> getOrderHistory(OrderHistoryReq req) {
        return okxClient.get(
                RequestPath.TRADE_ORDER_HISTORY,
                req,
                true,
                OrderInfoResp.class
        );
    }

    /**
     * Gets trade fills for reconciliation, fee calculation, and execution tracking.
     */
    public CommonResponse<FillResp> getFills(FillsReq req) {
        return okxClient.get(
                RequestPath.TRADE_FILLS,
                req,
                true,
                FillResp.class
        );
    }

    /**
     * Arms or cancels OKX's dead-man switch that cancels all orders after the timeout expires.
     */
    public CommonResponse<OrderActionResp> cancelAllAfter(CancelAllAfterReq req) {
        return okxClient.post(
                RequestPath.TRADE_CANCEL_ALL_AFTER,
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
