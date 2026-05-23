package com.trade.polymarket.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class PolymarketMarketSnapshot {
    private String id;
    private String conditionId;
    private String slug;
    private String question;
    private String description;
    private String category;
    private String endDate;
    private Boolean active;
    private Boolean closed;
    private Boolean archived;
    private Boolean restricted;
    private Boolean enableOrderBook;
    private Boolean acceptingOrders;
    private String volume24hr;
    private String liquidity;
    private String orderMinSize;
    private String orderPriceMinTickSize;
    private Boolean negRisk;
    private List<PolymarketOutcomeSnapshot> outcomes;
}
