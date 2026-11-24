package com.trade.trading.application;

import com.trade.trading.port.LiveAccountLock;
import java.util.function.Supplier;

public final class LocalLiveAccountLock implements LiveAccountLock {
    @Override
    public synchronized <T> T withLock(Supplier<T> action) {
        return action.get();
    }
}
