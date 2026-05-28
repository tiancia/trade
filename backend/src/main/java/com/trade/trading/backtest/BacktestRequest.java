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
    private String strategyId;
    private String instId;
    private String bar;
    private Instant from;
    private Instant to;
    private BigDecimal initialCash = new BigDecimal("1000");
    private BigDecimal feeRate = new BigDecimal("0.001");
    private BigDecimal slippageRate = BigDecimal.ZERO;
    private Map<String, Object> parameterOverrides = new LinkedHashMap<>();
}
