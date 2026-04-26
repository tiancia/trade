package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class TradingState {
    private BigDecimal trackedBaseAmount = BigDecimal.ZERO;
    private BigDecimal averageCost = BigDecimal.ZERO;
    private String updatedAt;

    public boolean hasTrackedPosition() {
        return trackedBaseAmount != null
                && averageCost != null
                && trackedBaseAmount.signum() > 0
                && averageCost.signum() > 0;
    }
}
