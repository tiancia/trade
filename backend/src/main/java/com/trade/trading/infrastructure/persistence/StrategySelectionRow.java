package com.trade.trading.infrastructure.persistence;

import lombok.Data;
import lombok.experimental.Accessors;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class StrategySelectionRow {
    private Long id;
    private String accountScope;
    private String instId;
    private String strategyId;
    private long revision;
    private Instant changedAt;
}
