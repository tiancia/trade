package com.trade.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PlaceOrderReq {
    private String instId;
    private String tdMode;
    private String ccy;
    private String clOrdId;
    private String tag;
    private String side;
    private String posSide;
    private String ordType;
    private String sz;
    private String px;
    private String reduceOnly;
    private String tgtCcy;
    private String banAmend;
    private String quickMgnType;
    private String stpId;
    private String stpMode;
}
