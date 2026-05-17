package com.trade.client.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolymarketSamplingMarket {
    @JsonProperty("condition_id")
    private String conditionId;
    @JsonProperty("question_id")
    private String questionId;
    private String question;
    private String description;
    @JsonProperty("market_slug")
    private String marketSlug;
    @JsonProperty("end_date_iso")
    private String endDateIso;
    private Boolean active;
    private Boolean closed;
    private Boolean archived;
    @JsonProperty("enable_order_book")
    private Boolean enableOrderBook;
    @JsonProperty("accepting_orders")
    private Boolean acceptingOrders;
    @JsonProperty("minimum_order_size")
    private String minimumOrderSize;
    @JsonProperty("minimum_tick_size")
    private String minimumTickSize;
    @JsonProperty("neg_risk")
    private Boolean negRisk;
    private List<PolymarketSamplingToken> tokens;
}
