package com.trade.trading.order;

import com.trade.trading.persistence.SpotFillApplication;

/** Durable result of applying one exchange order snapshot. */
public record OrderSettlementResult(
        TradingOrder order,
        String executionStatus,
        SpotFillApplication fillApplication
) {
}
