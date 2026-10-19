package com.trade.trading.domain.paper;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable simulated execution with its original strategy configuration provenance. */
public record PaperFill(Long id, String sessionId, String decisionId, String strategyId,
                        long strategyConfigVersion, String strategyConfigSnapshotJson, String side,
                        BigDecimal requestedSize, BigDecimal filledBase, BigDecimal positionDelta,
                        BigDecimal price, BigDecimal cashDelta, BigDecimal feeQuote,
                        BigDecimal realizedPnl, BigDecimal cashAfter, BigDecimal positionAfter,
                        BigDecimal equityAfter, long accountVersion, Instant createdAt) {}
