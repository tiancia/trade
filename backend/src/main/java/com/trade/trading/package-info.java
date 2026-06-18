/**
 * OKX strategy-trading domain.
 *
 * <p>The runtime flow is: scheduler or event scan triggers the strategy engine,
 * market context is collected from OKX, configured strategies evaluate the
 * context, risk and sizing are applied through the broker path, and local state
 * records the decision for future prompts and status APIs.</p>
 */
package com.trade.trading;
