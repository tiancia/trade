/**
 * Trading use cases: strategy registration, execution, settlement transactions, CAS retries,
 * reconciliation, history loading and runtime lifecycle.
 * Raw provider collection and audit envelopes live here; TradingMarketInputs converts
 * their values into provider-independent domain facts.
 *
 * <p>Business decisions and arithmetic are delegated to domain policies. Execution services
 * coordinate safety checks and external calls through ports; adapters implement the
 * OKX submission/query protocol. This layer owns I/O order, transaction boundaries and metrics.</p>
 */
package com.trade.trading.application;
