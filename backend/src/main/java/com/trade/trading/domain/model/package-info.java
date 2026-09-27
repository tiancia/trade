/**
 * Trading values, decisions, execution modes, and state snapshots.
 *
 * <p>These types describe data shared by use cases and adapters; they do not
 * orchestrate work or depend on application services or configuration classes.
 * Models specific to orders, risk, events, and backtests remain with those
 * cohesive capabilities rather than being collected in a global enum/DTO package.</p>
 */
package com.trade.trading.domain.model;
