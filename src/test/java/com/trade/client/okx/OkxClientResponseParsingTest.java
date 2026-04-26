package com.trade.client.okx;

import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.OrderActionResp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OkxClientResponseParsingTest {

    @Test
    void parsesOrderActionBusinessFailureBodyWithoutTreatingItAsJsonParseError() {
        String body = """
                {
                  "code": "1",
                  "msg": "Operation failed.",
                  "data": [{
                    "ordId": "",
                    "clOrdId": "client-1",
                    "sCode": "51008",
                    "sMsg": "Order failed. Insufficient balance."
                  }]
                }
                """;

        OkxClient client = new StubOkxClient(body);

        OkxResponse<OrderActionResp> response = client.post(
                OkxEndpoints.TRADE_ORDER,
                null,
                true,
                OrderActionResp.class
        );

        assertEquals("1", response.getCode());
        assertEquals("Operation failed.", response.getMsg());
        assertEquals("client-1", response.getData().get(0).getClOrdId());
        assertEquals("51008", response.getData().get(0).getSCode());
    }

    private static class StubOkxClient extends OkxClient {
        private final String body;

        StubOkxClient(String body) {
            this.body = body;
        }

        @Override
        public String postRaw(String path, Object req, boolean needAuth) {
            return body;
        }
    }
}
