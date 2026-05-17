package com.trade.client.okx.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Request for OKX account instrument metadata.
 *
 * GET /api/v5/account/instruments
 */
@Data
@Accessors(chain = true)
public class InstrumentInfoReq {
    private String instType;
    private String seriesId;
    private String instFamily;
    private String instId;
}
