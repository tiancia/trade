package com.trade.client.okx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/** Public OKX order-book snapshot. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderBookResp {
    /** Ask levels ordered from best to worst. */
    private List<OrderBookLevel> asks;
    /** Bid levels ordered from best to worst. */
    private List<OrderBookLevel> bids;
    /** Exchange timestamp in epoch milliseconds. */
    private String ts;
    /** Sequence identifier used to order book updates. */
    private Long seqId;
}
