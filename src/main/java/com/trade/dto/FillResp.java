package com.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FillResp {
    private String instType;
    private String instId;
    private String tradeId;
    private String ordId;
    private String clOrdId;
    private String billId;
    private String tag;
    private String fillPx;
    private String fillSz;
    private String side;
    private String posSide;
    private String execType;
    private String feeCcy;
    private String fee;
    private String ts;
}
