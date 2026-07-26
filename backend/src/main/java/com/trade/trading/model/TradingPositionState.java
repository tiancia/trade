package com.trade.trading.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Durable position and cost projection for one managed instrument.
 *
 * <p>The managed quantity is derived from idempotently applied fills. The
 * optional exchange quantity is an observation used by reconciliation; it is
 * not silently substituted for the managed quantity unless the configured
 * account is explicitly dedicated to this trading process.</p>
 */
@Data
@Accessors(chain = true)
public class TradingPositionState {
    private String accountScope;
    private String instId;
    private String positionSide = "net";
    private BigDecimal quantity = BigDecimal.ZERO;
    private BigDecimal averageCost = BigDecimal.ZERO;
    private BigDecimal exchangeQuantity;
    private Instant lastReconciledAt;
    private long version;
}
