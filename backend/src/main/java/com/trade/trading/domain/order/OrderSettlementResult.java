package com.trade.trading.domain.order;



/** Durable result of applying one exchange order snapshot. */
public record OrderSettlementResult(
        TradingOrder order,
        String executionStatus,
        SpotFillApplication fillApplication
) {
}
