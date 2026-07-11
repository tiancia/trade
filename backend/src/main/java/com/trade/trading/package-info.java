/**
 * OKX strategy-trading domain.
 *
 * <p>The runtime flow is: scheduler or event scan triggers the strategy engine,
 * market context is collected from OKX, configured strategies evaluate the
 * context, risk and sizing are applied through the broker path, and local state
 * records the decision for later evaluations, audit, and status APIs. Main
 * entry points are {@link com.trade.trading.web.TradingController},
 * {@link com.trade.trading.scheduler.TradingScheduler}, and
 * {@link com.trade.trading.application.TradingStrategyEngine}.</p>
 */
package com.trade.trading;
