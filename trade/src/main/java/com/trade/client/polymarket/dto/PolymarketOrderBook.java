package com.trade.client.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolymarketOrderBook {
    private String market;
    @JsonProperty("asset_id")
    private String assetId;
    private String timestamp;
    private String hash;
    private List<PolymarketOrderBookLevel> bids;
    private List<PolymarketOrderBookLevel> asks;
    @JsonProperty("min_order_size")
    private String minOrderSize;
    @JsonProperty("tick_size")
    private String tickSize;
    @JsonProperty("neg_risk")
    private Boolean negRisk;
    @JsonProperty("last_trade_price")
    private String lastTradePrice;
}
