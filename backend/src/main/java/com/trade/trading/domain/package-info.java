/**
 * Trading business rules and state: order identity, lifecycle, settlement and cost accounting;
 * risk and account valuation; market signals and threshold decisions; simulated portfolio and statistics.
 *
 * <p>Order and position snapshots expose named operations instead of mutable setters;
 * persistence rehydration is separate from business transitions.</p>
 *
 * <p>Rules use resolved facts and do not depend on use-case services, framework components,
 * persistence rows, configuration bindings or provider DTOs. Application adapters supply
 * provider-independent facts to these rules.</p>
 */
package com.trade.trading.domain;
