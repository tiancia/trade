package com.trade.dto;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.common.CommonResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OkxDtoMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsTickerResponse() throws Exception {
        String json = """
                {
                  "code": "0",
                  "msg": "",
                  "data": [{
                    "instType": "SPOT",
                    "instId": "BTC-USDT",
                    "last": "77531.4",
                    "askPx": "77531.5",
                    "bidPx": "77531.3",
                    "ts": "1777103098207"
                  }]
                }
                """;

        CommonResponse<TickerResp> resp = read(json, TickerResp.class);

        assertEquals("0", resp.getCode());
        assertEquals("BTC-USDT", resp.getData().get(0).getInstId());
        assertEquals("77531.4", resp.getData().get(0).getLast());
    }

    @Test
    void mapsOrderBookArrayLevels() throws Exception {
        String json = """
                {
                  "code": "0",
                  "msg": "",
                  "data": [{
                    "asks": [["77531.4", "1.14795564", "0", "18"]],
                    "bids": [["77531.3", "0.66198167", "0", "6"]],
                    "ts": "1777103098654",
                    "seqId": 75530679712
                  }]
                }
                """;

        CommonResponse<OrderBookResp> resp = read(json, OrderBookResp.class);

        OrderBookResp book = resp.getData().get(0);
        assertEquals("77531.4", book.getAsks().get(0).getPx());
        assertEquals("1.14795564", book.getAsks().get(0).getSz());
        assertEquals("6", book.getBids().get(0).getOrders());
    }

    @Test
    void mapsCandleArrayRows() throws Exception {
        String json = """
                {
                  "code": "0",
                  "msg": "",
                  "data": [[
                    "1777103040000",
                    "77533.4",
                    "77540",
                    "77531.3",
                    "77531.3",
                    "1.67774653",
                    "130084.508745623",
                    "130084.508745623",
                    "0"
                  ]]
                }
                """;

        CommonResponse<CandleResp> resp = read(json, CandleResp.class);

        CandleResp candle = resp.getData().get(0);
        assertEquals("1777103040000", candle.getTs());
        assertEquals("77533.4", candle.getOpen());
        assertEquals("0", candle.getConfirm());
    }

    @Test
    void mapsOrderActionResponse() throws Exception {
        String json = """
                {
                  "code": "0",
                  "msg": "",
                  "data": [{
                    "ordId": "1",
                    "clOrdId": "client-1",
                    "sCode": "0",
                    "sMsg": ""
                  }]
                }
                """;

        CommonResponse<OrderActionResp> resp = read(json, OrderActionResp.class);

        assertNotNull(resp.getData());
        assertEquals("1", resp.getData().get(0).getOrdId());
        assertEquals("0", resp.getData().get(0).getSCode());
    }

    private <T> CommonResponse<T> read(String json, Class<T> dataClass) throws Exception {
        JavaType javaType = objectMapper.getTypeFactory()
                .constructParametricType(CommonResponse.class, dataClass);
        return objectMapper.readValue(json, javaType);
    }
}
