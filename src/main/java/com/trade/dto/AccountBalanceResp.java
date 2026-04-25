package com.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountBalanceResp {
    private String uTime;
    private String totalEq;
    private String isoEq;
    private String adjEq;
    private String ordFroz;
    private String imr;
    private String mmr;
    private String borrowFroz;
    private String mgnRatio;
    private String notionalUsd;
    private String detailsEq;
    private List<BalanceDetail> details;
}
