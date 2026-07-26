package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class TradingRiskState {
    private BigDecimal currentEquity = BigDecimal.ZERO;
    private BigDecimal equityHighWatermark = BigDecimal.ZERO;
    private BigDecimal dayStartEquity = BigDecimal.ZERO;
    private String dayStartDate;
    private int consecutiveLosses;
    private String lossCooldownUntil;
    private String lastTradeTime;
    private int consecutiveOpenActions;
    private String lastRiskReason;
    private int consecutiveReconciliationFailures;
    private String lastReconciliationAt;
    private String lastReconciliationError;
}
