package com.trade.client.okx.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class OrderBookReq {
    private String instId;
    private String sz;
}
