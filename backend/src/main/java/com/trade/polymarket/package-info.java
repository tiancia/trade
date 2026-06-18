/**
 * Polymarket AI trading domain.
 *
 * <p>The main flow is: collect candidate markets, build an AI prompt, parse a
 * decision, pass valid orders to the executor, and persist an audit trail for
 * every run.</p>
 */
package com.trade.polymarket;
