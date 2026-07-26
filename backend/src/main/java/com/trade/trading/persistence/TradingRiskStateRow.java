package com.trade.trading.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class TradingRiskStateRow {
    private String accountScope;
    private BigDecimal currentEquity;
    private BigDecimal equityHighWatermark;
    private BigDecimal dayStartEquity;
    private String dayStartDate;
    private int consecutiveLosses;
    private Instant lossCooldownUntil;
    private Instant lastTradeTime;
    private int consecutiveOpenActions;
    private String lastRiskReason;
    private int consecutiveReconciliationFailures;
    private Instant lastReconciliationAt;
    private String lastReconciliationError;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;
}
