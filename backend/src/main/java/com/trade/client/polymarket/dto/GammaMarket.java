package com.trade.client.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GammaMarket {
    private String id;
    private String conditionId;
    private String question;
    private String description;
    private String slug;
    private String category;
    private String endDate;
    private Boolean active;
    private Boolean closed;
    private Boolean archived;
    private Boolean restricted;
    private Boolean enableOrderBook;
    private Boolean acceptingOrders;
    private Boolean negRisk;
    private String orderMinSize;
    private String orderPriceMinTickSize;
    private String volume;
    private String volumeNum;
    private String volume24hr;
    private String liquidity;
    private String liquidityNum;
    private String bestBid;
    private String bestAsk;
    private String lastTradePrice;
    private JsonNode outcomes;
    private JsonNode outcomePrices;
    private JsonNode clobTokenIds;
    @JsonProperty("fpmm")
    private String fixedProductMarketMaker;
}
