package com.trade.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PendingOrdersReq {
    private String instType;
    private String uly;
    private String instFamily;
    private String instId;
    private String ordType;
    private String state;
    private String after;
    private String before;
    private String limit;
}
