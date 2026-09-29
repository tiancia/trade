package com.trade.polymarket.domain.model;

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
    private BigDecimal topBidLiquidityUsdc;
    private BigDecimal topAskLiquidityUsdc;
    private String minOrderSize;
    private String tickSize;
    private Boolean negRisk;
    private List<MarketDepthLevel> topBids;
    private List<MarketDepthLevel> topAsks;
    private String orderBookError;
}
