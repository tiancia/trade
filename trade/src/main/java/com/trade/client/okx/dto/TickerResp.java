package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TickerResp {
    private String instType;
    private String instId;
    private String last;
    private String lastSz;
    private String askPx;
    private String askSz;
    private String bidPx;
    private String bidSz;
    private String open24h;
    private String high24h;
    private String low24h;
    private String volCcy24h;
    private String vol24h;
    private String ts;
    private String sodUtc0;
    private String sodUtc8;
}
