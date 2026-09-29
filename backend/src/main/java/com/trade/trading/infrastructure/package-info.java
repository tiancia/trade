/**
 * Trading technical implementations, grouped by capability.
 *
 * <p>{@code broker} executes orders; {@code persistence} implements database,
 * file, and cache access; {@code market} adapts REST/WebSocket sources;
 * {@code event} owns bounded delivery and handlers; {@code config} wires beans.
 * State, market, and pipeline implementations expose application ports. Outbound contracts belong to {@code com.trade.trading.application.port}.</p>
 */
package com.trade.trading.infrastructure;
