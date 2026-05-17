package com.trade.client.okx;

import com.trade.client.okx.dto.OkxResponse;
import com.trade.client.okx.dto.InstrumentInfoReq;
import com.trade.client.okx.dto.InstrumentInfoResp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class OkxApiIntegrationTest {

    @Test
    void testGetInstrumentInfo() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_INTEGRATION_TESTS")));
        assumeTrue(hasEnv("OKX-ACCESS-KEY", "OKX_ACCESS_KEY", "ak")
                && hasEnv("OKX-ACCESS-PASSPHRASE", "OKX_ACCESS_PASSPHRASE", "ap")
                && hasEnv("OKX-SECRET-KEY", "OKX_SECRET_KEY", "sk"));

        OkxClient okxClient = new OkxClient();
        OkxApi okxApi = new OkxApi(okxClient);

        InstrumentInfoReq req = new InstrumentInfoReq();
        req.setInstType("SPOT");

        OkxResponse<InstrumentInfoResp> resp = okxApi.getInstrumentInfo(req);

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

    private boolean hasEnv(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
