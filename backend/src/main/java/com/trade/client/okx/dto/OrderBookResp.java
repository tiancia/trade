package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderBookResp {
    private List<OrderBookLevel> asks;
    private List<OrderBookLevel> bids;
    private String ts;
    private Long seqId;
}
