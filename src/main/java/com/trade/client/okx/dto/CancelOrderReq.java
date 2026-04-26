package com.trade.client.okx.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CancelOrderReq {
    private String instId;
    private String ordId;
    private String clOrdId;
}
