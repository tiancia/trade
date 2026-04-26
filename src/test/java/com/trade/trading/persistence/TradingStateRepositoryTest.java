package com.trade.trading.persistence;

import com.trade.trading.model.TradingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingStateRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsWeightedAverageCostAndSellReduction() {
        Path stateFile = tempDir.resolve("trading-state.json");
        TradingStateRepository repository = new TradingStateRepository(stateFile);

        repository.recordBuy(new BigDecimal("0.1"), new BigDecimal("50000"));
        repository.recordBuy(new BigDecimal("0.1"), new BigDecimal("60000"));

        TradingState loaded = new TradingStateRepository(stateFile).getState();
        assertDecimal("0.2", loaded.getTrackedBaseAmount());
        assertDecimal("55000", loaded.getAverageCost());

        repository.recordSell(new BigDecimal("0.05"));
        TradingState afterPartialSell = new TradingStateRepository(stateFile).getState();
        assertDecimal("0.15", afterPartialSell.getTrackedBaseAmount());
        assertDecimal("55000", afterPartialSell.getAverageCost());

        repository.recordSell(new BigDecimal("1"));
        TradingState afterFullSell = new TradingStateRepository(stateFile).getState();
        assertDecimal("0", afterFullSell.getTrackedBaseAmount());
        assertDecimal("0", afterFullSell.getAverageCost());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
