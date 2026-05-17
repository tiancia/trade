package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class AiTradingDecision {
    private TradingAction action;
    private String reason;
    private BigDecimal buyQuoteAmountUsdt;
    private BigDecimal sellBaseAmountBtc;
    private BigDecimal orderSize;
    private BigDecimal winProbability;
    private BigDecimal confidence;
    private String objectiveAlignment;
    private BigDecimal expectedNetEdgePercent;
    private BigDecimal riskRewardRatio;
    private String thesisChangeEvidence;
    private String strategyBias;
    private String strategyThesis;
    private String strategyInvalidation;
    private String strategyHorizon;
    private String rawResponse;

    public static AiTradingDecision hold(String reason, String rawResponse) {
        return new AiTradingDecision()
                .setAction(TradingAction.HOLD)
                .setReason(reason)
                .setRawResponse(rawResponse);
    }
}
