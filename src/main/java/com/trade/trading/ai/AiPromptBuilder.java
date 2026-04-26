package com.trade.trading.ai;

import org.springframework.stereotype.Component;

@Component
public class AiPromptBuilder {

    public String buildPrompt(String aiParametersJson) {
        return """
                You are an automated crypto spot trading decision engine.
                Trade only BTC-USDT spot. You may choose BUY, HOLD, or SELL.
                Use the provided market, account, order, fill, instrument-rule, and local cost-basis data.
                Respect these hard limits: BUY size is quote USDT and will be capped by the system; SELL size is BTC and will be capped by available BTC.
                Return exactly one JSON object. Do not include markdown, comments, or extra text.

                Required JSON schema:
                {
                  "action": "BUY|HOLD|SELL",
                  "reason": "short decision reason",
                  "buyQuoteAmountUsdt": 0,
                  "sellBaseAmountBtc": 0
                }

                Rules:
                - For BUY, set a positive buyQuoteAmountUsdt and omit or set sellBaseAmountBtc to 0.
                - For SELL, set a positive sellBaseAmountBtc and omit or set buyQuoteAmountUsdt to 0.
                - For HOLD, omit both amounts or set both to 0.
                - If data is insufficient, choose HOLD and explain why.

                AI input parameters:
                %s
                """.formatted(aiParametersJson);
    }
}
