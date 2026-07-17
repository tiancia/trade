package com.trade.trading.backtest;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class BacktestRequest {
    public static final int DEFAULT_MAX_CANDLES = 10_000;

    private String strategyId;
    private String instId;
    private String bar;
    private Instant from;
    private Instant to;
    private BigDecimal initialCash = new BigDecimal("1000");
    private BigDecimal feeRate = new BigDecimal("0.001");
    private BigDecimal slippageRate = BigDecimal.ZERO;
    /** Close any remaining long exposure on the final candle close. */
    private boolean forceCloseAtEnd = true;
    /** Include the exchange's still-forming candles. Disabled for reproducible runs. */
    private boolean includeUnconfirmed;
    /** Guardrail for API pagination, memory use, and accidental oversized runs. */
    private int maxCandles = DEFAULT_MAX_CANDLES;
    private Map<String, Object> parameterOverrides = new LinkedHashMap<>();
}
