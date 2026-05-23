package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PositionResp {
    private String instType;
    private String instId;
    private String posId;
    private String posSide;
    private String pos;
    private String availPos;
    private String avgPx;
    private String upl;
    private String uplRatio;
    private String lever;
    private String mgnMode;
    private String margin;
    private String liqPx;
    private String markPx;
    private String notionalUsd;
    private String cTime;
    private String uTime;
}
