/**
 * Trading inbound adapters: HTTP/SSE under {@code web}, timed triggers under
 * {@code scheduler}. TradingScheduler delegates to TradingTriggerService; event status queries use TradingEventStatusService. Business orchestration remains in {@code application}.
 */
package com.trade.trading.interfaces;
