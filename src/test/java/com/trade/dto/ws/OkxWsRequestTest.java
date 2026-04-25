package com.trade.dto.ws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OkxWsRequestTest {

    @Test
    void tickerRequestBuildsArg() {
        OkxWsArg arg = new TickerChannelReq()
                .setInstId("BTC-USDT")
                .toArg();

        assertEquals("tickers", arg.getChannel());
        assertEquals("BTC-USDT", arg.getInstId());
    }

    @Test
    void ordersRequestBuildsPrivateArg() {
        OkxWsArg arg = new OrdersChannelReq()
                .setInstType("SPOT")
                .setInstId("BTC-USDT")
                .toArg();

        assertEquals("orders", arg.getChannel());
        assertEquals("SPOT", arg.getInstType());
        assertEquals("BTC-USDT", arg.getInstId());
    }
}
