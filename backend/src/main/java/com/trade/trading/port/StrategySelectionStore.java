package com.trade.trading.port;

import com.trade.trading.domain.model.ActiveStrategySelection;
import java.util.function.Supplier;

/** Shared active selection and the short database fence for strategy changes and execution reservations. */
public interface StrategySelectionStore {
    ActiveStrategySelection currentOrCreate(String scope, String instId, ActiveStrategySelection seed);
    ActiveStrategySelection select(String scope, String instId, String strategyId, Long expectedRevision);
    <T> T withLock(Supplier<T> action);
    boolean hasExposure();
}
