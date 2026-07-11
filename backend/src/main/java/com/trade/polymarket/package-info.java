/**
 * Polymarket AI trading domain.
 *
 * <p>The main flow is: collect candidate markets, build an AI prompt, parse a
 * decision, pass valid orders to the executor, and persist an audit trail for
 * every run. Follow
 * {@link com.trade.polymarket.scheduler.AiPolymarketScheduler} into
 * {@link com.trade.polymarket.application.AiPolymarketService} and finally
 * {@link com.trade.polymarket.execution.PolymarketOrderExecutor}.</p>
 */
package com.trade.polymarket;
