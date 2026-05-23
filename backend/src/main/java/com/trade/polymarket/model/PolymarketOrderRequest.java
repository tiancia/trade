package com.trade.polymarket.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class PolymarketOrderRequest {
    private String marketSlug;
    private String question;
    private String outcome;
    private String tokenId;
    private String side;
    private BigDecimal price;
    private BigDecimal spendUsdc;
    private BigDecimal size;
    private String orderType;
    private String tickSize;
    private Boolean negRisk;
}
