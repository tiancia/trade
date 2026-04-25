package com.trade.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class FillsReq {
    private String instType;
    private String uly;
    private String instFamily;
    private String instId;
    private String ordId;
    private String after;
    private String before;
    private String begin;
    private String end;
    private String limit;
}
