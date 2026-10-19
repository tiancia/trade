package com.trade.trading.infrastructure.persistence;

import lombok.Data;

import java.time.Instant;

@Data
public class StrategyConfigurationRow {
    private Long id;
    private String strategyId;
    private String strategyType;
    private String bar;
    private long configVersion;
    private String paramsJson;
    private String reason;
    private Instant changedAt;
}
