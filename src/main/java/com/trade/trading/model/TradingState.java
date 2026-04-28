package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class TradingState {
    private BigDecimal trackedBaseAmount = BigDecimal.ZERO;
    private BigDecimal averageCost = BigDecimal.ZERO;
    private String updatedAt;
    private List<TradingDecisionRecord> recentDecisions = new ArrayList<>();

    public boolean hasTrackedPosition() {
        return trackedBaseAmount != null
                && averageCost != null
                && trackedBaseAmount.signum() > 0
                && averageCost.signum() > 0;
    }
}
