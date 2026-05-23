package com.trade.client.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolymarketSamplingToken {
    @JsonProperty("token_id")
    private String tokenId;
    private String outcome;
    private String price;
    private Boolean winner;
}
