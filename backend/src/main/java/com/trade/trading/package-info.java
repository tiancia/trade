/**
 * OKX strategy-trading domain.
 *
 * <p>The runtime flow is: REST and WebSocket market data enters the bounded
 * {@code trading.event} pipeline for isolated asynchronous persistence; a
 * scheduler or derived market signal triggers the strategy engine; configured
 * strategies evaluate one collected context; risk and sizing are applied
 * through the broker path; and local strategy memory records the decision.
 * Position, cost, risk, cumulative fills, and the fund-level stop are
 * authoritative in MySQL. {@code OrderReconciliationService} continuously
 * advances unresolved live orders without ever resubmitting them. A
 * database-backed leadership lease keeps scheduled work and LIVE submissions
 * single-writer across application instances. Main
 * entry points are {@link com.trade.trading.web.TradingController},
 * {@link com.trade.trading.scheduler.TradingScheduler}, and
 * {@link com.trade.trading.application.TradingStrategyEngine}.</p>
 */
package com.trade.trading;
