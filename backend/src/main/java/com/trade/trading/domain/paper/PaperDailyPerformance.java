package com.trade.trading.domain.paper;

import java.math.BigDecimal;

/** Session-scoped fills; a completed round trip is a SELL that leaves zero position. */
public record PaperDailyPerformance(long fillCount, long buyCount, long sellCount, long closedTrades,
                                    BigDecimal realizedNetPnl, BigDecimal feesQuote) {}
