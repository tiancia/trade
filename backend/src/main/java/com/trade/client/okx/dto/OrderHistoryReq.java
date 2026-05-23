package com.trade.client.okx.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class OrderHistoryReq {
    private String instType;
    private String uly;
    private String instFamily;
    private String instId;
    private String ordType;
    private String state;
    private String category;
    private String after;
    private String before;
    private String begin;
    private String end;
    private String limit;
}
