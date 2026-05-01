package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * OKX account instrument metadata response.
 *
 * GET /api/v5/account/instruments
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstrumentInfoResp {
    private String instType;
    private String instId;
    private Long instIdCode;
    private String uly;
    private String instFamily;
    private String baseCcy;
    private String quoteCcy;
    private String settleCcy;
    private String ctVal;
    private String ctMult;
    private String ctValCcy;
    private String ctType;
    private String optType;
    private String expTime;
    private String listTime;
    private String lever;
    private String minSz;
    private String lotSz;
    private String tickSz;
    private String maxLmtSz;
    private String maxMktSz;
    private String maxLmtAmt;
    private String maxMktAmt;
    private String maxStopSz;
    private String maxTriggerSz;
    private String maxIcebergSz;
    private String maxTwapSz;
    private String maxPlatOILmt;
    private String state;
    private String ruleType;
    private String openType;
    private String auctionEndTime;
    private String contTdSwTime;
    private String preMktSwTime;
    private List<String> tradeQuoteCcyList;
    private String longPosRemainingQuota;
    private String shortPosRemainingQuota;
    private String posLmtAmt;
    private String posLmtPct;
    private String instCategory;
    private String groupId;
    private Boolean futureSettlement;
    private String elp;
    private String stk;
    private String freq;
    private String method;
    private String seriesId;
    private List<Object> upcChg;
}
