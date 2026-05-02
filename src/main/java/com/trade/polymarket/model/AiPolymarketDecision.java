package com.trade.polymarket.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class AiPolymarketDecision {
    private PolymarketAction action;
    private String reason;
    private String marketId;
    private String marketSlug;
    private String marketQuestion;
    private String outcome;
    private String tokenId;
    private BigDecimal limitPrice;
    private BigDecimal maxSpendUsdc;
    private BigDecimal confidence;
    private BigDecimal estimatedProbability;
    private BigDecimal estimatedEdge;
    private String rawResponse;

    public static AiPolymarketDecision hold(String reason, String rawResponse) {
        return new AiPolymarketDecision()
                .setAction(PolymarketAction.HOLD)
                .setReason(reason)
                .setRawResponse(rawResponse);
    }
}
