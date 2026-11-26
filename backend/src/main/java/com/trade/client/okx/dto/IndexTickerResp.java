package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexTickerResp(String instId, String idxPx, String ts) {}
