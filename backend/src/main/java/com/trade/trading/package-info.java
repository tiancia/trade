/**
 * OKX strategy-trading domain.
 *
 * <p>The runtime flow is: REST and WebSocket market data enters the bounded
 * {@code infrastructure.event} pipeline for isolated asynchronous persistence; a
 * scheduler or derived market signal triggers the strategy engine; the active
 * strategy evaluates one collected context; risk and sizing are applied
 * through the broker path; and local strategy memory records the decision.
 * Position, cost, risk, cumulative fills, and the fund-level stop are
 * authoritative in MySQL. {@code OrderReconciliationService} continuously
 * advances unresolved live orders without ever resubmitting them. A
 * database-backed leadership lease keeps scheduled work and LIVE submissions
 * single-writer across application instances. Main
 * entry points are {@link com.trade.trading.interfaces.web.TradingController},
 * {@link com.trade.trading.interfaces.scheduler.TradingScheduler}, and
 * {@link com.trade.trading.application.strategy.TradingStrategyEngine}.</p>
 *
 * <p>Read {@code interfaces} for HTTP and scheduled entry points,
 * {@code application} for use cases grouped by capability, {@code domain} for
 * data and pure rules, and {@code infrastructure} for persistence, brokers,
 * market feeds, event delivery, and configuration. Outbound contracts live in
 * {@code application.port}; backtest fills are independent domain values.</p>
 */
package com.trade.trading;
