package com.trade.client.okx;

import com.trade.common.CommonResponse;
import com.trade.dto.InstrumentInfoReq;
import com.trade.dto.InstrumentInfoResp;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

class OkxApiIntegrationTest {

    @Test
    void testGetInstrumentInfo() {
        assumeTrue(hasEnv("ak", "OKX_ACCESS_KEY")
                && hasEnv("ap", "OKX_ACCESS_PASSPHRASE")
                && hasEnv("sk", "OKX_SECRET_KEY"));

        OkxClient okxClient = new OkxClient();
        OkxApi okxApi = new OkxApi(okxClient);

        InstrumentInfoReq req = new InstrumentInfoReq();
        req.setInstType("SPOT");

        CommonResponse<InstrumentInfoResp> resp = okxApi.getInstrumentInfo(req);

        assertNotNull(resp);
        assertEquals("0", resp.getCode());
        assertNotNull(resp.getData());
        assertFalse(resp.getData().isEmpty());

        InstrumentInfoResp item = resp.getData().get(0);

        assertEquals("SPOT", item.getInstType());

        System.out.println("code = " + resp.getCode());
        System.out.println("msg = " + resp.getMsg());
        System.out.println("instId = " + item.getInstId());
        System.out.println("baseCcy = " + item.getBaseCcy());
        System.out.println("quoteCcy = " + item.getQuoteCcy());
    }

    private boolean hasEnv(String primaryName, String fallbackName) {
        String value = System.getenv(primaryName);
        if (value == null || value.isBlank()) {
            value = System.getenv(fallbackName);
        }
        return value != null && !value.isBlank();
    }
}
