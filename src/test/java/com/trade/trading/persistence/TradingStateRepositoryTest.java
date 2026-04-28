package com.trade.trading.persistence;

import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionRecord;
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

    @Test
    void persistsRecentDecisionsNewestFirstWithLimit() {
        Path stateFile = tempDir.resolve("trading-state.json");
        TradingStateRepository repository = new TradingStateRepository(stateFile);

        repository.recordDecision(decision("1", TradingAction.BUY), 2);
        repository.recordDecision(decision("2", TradingAction.HOLD), 2);
        repository.recordDecision(decision("3", TradingAction.SELL), 2);

        TradingState loaded = new TradingStateRepository(stateFile).getState();
        assertEquals(2, loaded.getRecentDecisions().size());
        assertEquals("3", loaded.getRecentDecisions().get(0).getTimestamp());
        assertEquals(TradingAction.SELL, loaded.getRecentDecisions().get(0).getAction());
        assertEquals("2", loaded.getRecentDecisions().get(1).getTimestamp());
        assertEquals(TradingAction.HOLD, loaded.getRecentDecisions().get(1).getAction());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static TradingDecisionRecord decision(String timestamp, TradingAction action) {
        return new TradingDecisionRecord()
                .setTimestamp(timestamp)
                .setAction(action)
                .setReason("test")
                .setExecutionStatus("FILLED");
    }
}
