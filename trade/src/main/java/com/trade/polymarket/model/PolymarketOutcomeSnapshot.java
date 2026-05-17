package com.trade.polymarket.model;

import com.trade.client.polymarket.dto.PolymarketOrderBookLevel;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

@Data
@Accessors(chain = true)
public class PolymarketOutcomeSnapshot {
    private String outcome;
    private String tokenId;
    private BigDecimal gammaPrice;
    private BigDecimal lastTradePrice;
    private BigDecimal bestBid;
    private BigDecimal bestAsk;
    private BigDecimal midPrice;
    private BigDecimal spread;
    private String minOrderSize;
    private String tickSize;
    private Boolean negRisk;
    private List<PolymarketOrderBookLevel> topBids;
    private List<PolymarketOrderBookLevel> topAsks;
    private String orderBookError;
}
