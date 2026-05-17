package com.trade.polymarket.model;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PolymarketOrderResult {
    private boolean placed;
    private boolean dryRun;
    private String status;
    private String responseBody;
    private String skipReason;

    public static PolymarketOrderResult skipped(String reason) {
        return new PolymarketOrderResult()
                .setPlaced(false)
                .setStatus("SKIPPED")
                .setSkipReason(reason);
    }

    public static PolymarketOrderResult dryRun(String responseBody) {
        return new PolymarketOrderResult()
                .setPlaced(false)
                .setDryRun(true)
                .setStatus("DRY_RUN")
                .setResponseBody(responseBody);
    }

    public static PolymarketOrderResult placed(String responseBody) {
        return new PolymarketOrderResult()
                .setPlaced(true)
                .setStatus("PLACED")
                .setResponseBody(responseBody);
    }
}
