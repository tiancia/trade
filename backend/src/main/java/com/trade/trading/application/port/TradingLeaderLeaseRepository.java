package com.trade.trading.application.port;

import com.trade.trading.application.TradingLeaderLease;

import java.time.Duration;

/** Atomic database operations used to acquire, renew, inspect, and release leadership. */
public interface TradingLeaderLeaseRepository {

    TradingLeaderLease acquireOrRenew(String leaseName, String ownerId, Duration leaseDuration);

    TradingLeaderLease find(String leaseName);

    boolean release(String leaseName, String ownerId);
}
