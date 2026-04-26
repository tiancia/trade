package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BalanceDetail {
    private String ccy;
    private String eq;
    private String cashBal;
    private String uTime;
    private String isoEq;
    private String availEq;
    private String disEq;
    private String availBal;
    private String frozenBal;
    private String ordFrozen;
    private String liab;
    private String upl;
    private String uplLiab;
    private String crossLiab;
    private String rewardBal;
    private String eqUsd;
    private String borrowFroz;
    private String notionalLever;
    private String stgyEq;
    private String isoUpl;
    private String spotInUseAmt;
    private String clSpotInUseAmt;
    private String maxLoan;
    private String spotIsoBal;
    private String imr;
    private String mmr;
    private String smtSyncEq;
}
