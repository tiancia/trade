package com.trade.trading.web;

/** Operator request to atomically replace the currently active strategy. */
public record ActivateStrategyRequest(String strategyId, Long expectedRevision) {
}
