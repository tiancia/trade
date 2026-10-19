package com.trade.trading.domain.paper;

import java.math.BigDecimal;
import java.time.Instant;

public record PaperEquityPoint(Long id, String sessionId, Instant timestamp, BigDecimal cash,
                               BigDecimal positionQuantity, BigDecimal lastPrice, Instant valuationAt, BigDecimal equity,
                               BigDecimal realizedPnl, BigDecimal unrealizedPnl, long accountVersion,
                               String strategyId, Long strategyConfigVersion, String kind) {}
