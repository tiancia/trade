/**
 * Trading use-case orchestration and runtime lifecycle services.
 *
 * <p>Start with {@link com.trade.trading.application.strategy.TradingStrategyEngine} for
 * decisions, {@link com.trade.trading.application.order.OrderReconciliationService}
 * for reconciliation, and {@link com.trade.trading.application.runtime.TradingLeadershipService}
 * for single-writer ownership. Data snapshots and business enums belong to
 * {@code com.trade.trading.domain.model}; outbound contracts belong to {@code application.port}.</p>
 */
package com.trade.trading.application;
