package com.trade.trading.port;

import java.util.function.Supplier;

/** Serializes LIVE exchange actions for the shared account across application instances. */
public interface LiveAccountLock {
    <T> T withLock(Supplier<T> action);
}
