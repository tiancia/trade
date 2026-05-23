package com.trade.client.polymarket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolymarketSamplingMarketsPage {
    private Integer limit;
    private Integer count;
    private List<PolymarketSamplingMarket> data;
    @JsonProperty("next_cursor")
    private String nextCursor;
}
