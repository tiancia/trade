package com.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderInfoResp {
    private String instType;
    private String instId;
    private String tgtCcy;
    private String ccy;
    private String ordId;
    private String clOrdId;
    private String tag;
    private String px;
    private String sz;
    private String pnl;
    private String ordType;
    private String side;
    private String posSide;
    private String tdMode;
    private String accFillSz;
    private String fillPx;
    private String tradeId;
    private String fillSz;
    private String fillTime;
    private String avgPx;
    private String state;
    private String lever;
    private String attachAlgoClOrdId;
    private String tpTriggerPx;
    private String tpTriggerPxType;
    private String tpOrdPx;
    private String slTriggerPx;
    private String slTriggerPxType;
    private String slOrdPx;
    private String feeCcy;
    private String fee;
    private String rebateCcy;
    private String rebate;
    private String source;
    private String category;
    private String reduceOnly;
    private String cancelSource;
    private String cancelSourceReason;
    private String quickMgnType;
    private String stpId;
    private String stpMode;
    private String uTime;
    private String cTime;
}
