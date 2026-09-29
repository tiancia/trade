/**
 * Polymarket AI trading domain.
 * Provider depth is copied into immutable domain MarketDepthLevel observations at collection.
 *
 * <p>The main flow is: collect candidate markets, build an AI prompt, parse a
 * decision, pass valid orders to the executor, and persist an audit trail for
 * every run. Market horizon, turnover, and liquidity rules live in domain.rule and are shared by collection and execution. Follow
 * {@link com.trade.polymarket.interfaces.scheduler.AiPolymarketScheduler} into
 * {@link com.trade.polymarket.application.service.AiPolymarketService} and finally
 * {@link com.trade.polymarket.application.execution.PolymarketOrderExecutor}.</p>
 */
package com.trade.polymarket;
