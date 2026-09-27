package com.trade.trading.domain.model;

/** Execution environment shared by configuration, brokers, and runtime snapshots. */
public enum ExecutionMode {
    PAPER,
    LIVE,
    BACKTEST
}
