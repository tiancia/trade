package com.trade.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class OrderQueryReq {
    private String instId;
    private String ordId;
    private String clOrdId;
}
