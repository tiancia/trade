package com.trade.trading;

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
    private String rawResponse;

    public static AiTradingDecision hold(String reason, String rawResponse) {
        return new AiTradingDecision()
                .setAction(TradingAction.HOLD)
                .setReason(reason)
                .setRawResponse(rawResponse);
    }
}
