package com.trade.trading.infrastructure.persistence;

import com.trade.trading.port.TradingFinancialStateStore;
import com.trade.trading.domain.model.AiTradingDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.model.TradingRiskState;
import com.trade.trading.domain.model.TradingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingStateRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void failedWriteDoesNotActivateStrategyOrAdvanceRevision() throws Exception {
        Path parent = tempDir.resolve("blocked-parent");
        TradingStateRepository repository = new TradingStateRepository(parent.resolve("state.json"));
        repository.getState();
        Files.writeString(parent, "a file prevents creation of the state directory");

        assertThrows(IllegalStateException.class, () -> repository.selectActiveStrategy("defensive", 0L));

        assertNull(repository.getState().getActiveStrategyId());
        assertEquals(0L, repository.getState().getActiveStrategyRevision());
        assertEquals("a file prevents creation of the state directory", Files.readString(parent));
    }

    @Test
    void failedSerializationPreservesCompleteFileAndDecisionMemory() throws Exception {
        Path file = tempDir.resolve("state.json");
        TradingStateRepository repository = new TradingStateRepository(file);
        repository.selectActiveStrategy("balanced", 0L);
        repository.recordDecision(decision("old", TradingAction.HOLD), 2);
        String committed = Files.readString(file);
        TradingDecisionRecord invalid = decision("new", TradingAction.BUY)
                .setMetadata(Map.of("invalid", new UnserializableValue()));

        assertThrows(IllegalStateException.class, () -> repository.recordDecision(invalid, 2));

        assertEquals(committed, Files.readString(file));
        assertEquals(1, repository.getState().getRecentDecisions().size());
        assertEquals("old", repository.getState().getRecentDecisions().getFirst().getTimestamp());
        assertEquals("balanced", new TradingStateRepository(file).getState().getActiveStrategyId());
        try (var entries = Files.list(tempDir)) {
            assertEquals(1L, entries.count());
        }
    }

    public static class UnserializableValue {
        public String getValue() {
            throw new IllegalStateException("serialization failure");
        }
    }

    @Test
    void persistsWeightedAverageCostAndSellReduction() {
        Path stateFile = tempDir.resolve("trading-state.json");
        InMemoryTradingFinancialStateStore financialStore = new InMemoryTradingFinancialStateStore();
        TradingStateRepository repository = repository(stateFile, financialStore);

        repository.recordBuy(new BigDecimal("0.1"), new BigDecimal("50000"));
        repository.recordBuy(new BigDecimal("0.1"), new BigDecimal("60000"));

        TradingState loaded = repository(stateFile, financialStore).getState();
        assertDecimal("0.2", loaded.getTrackedBaseAmount());
        assertDecimal("55000", loaded.getAverageCost());

        repository.recordSell(new BigDecimal("0.05"));
        TradingState afterPartialSell = repository(stateFile, financialStore).getState();
        assertDecimal("0.15", afterPartialSell.getTrackedBaseAmount());
        assertDecimal("55000", afterPartialSell.getAverageCost());

        repository.recordSell(new BigDecimal("1"));
        TradingState afterFullSell = repository(stateFile, financialStore).getState();
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

    @Test
    void persistsStrategyStateAcrossRepositoryReloads() {
        Path stateFile = tempDir.resolve("trading-state.json");
        TradingStateRepository repository = new TradingStateRepository(stateFile);
        String decisionId = "1001";

        repository.recordStrategyState(decisionId, new AiTradingDecision()
                .setStrategyBias("LONG")
                .setStrategyThesis("trend continuation")
                .setStrategyInvalidation("break below support")
                .setStrategyHorizon("2-5 days"));

        TradingState loaded = new TradingStateRepository(stateFile).getState();
        assertEquals("LONG", loaded.getStrategyState().getBias());
        assertEquals("trend continuation", loaded.getStrategyState().getThesis());
        assertEquals("break below support", loaded.getStrategyState().getInvalidation());
        assertEquals("2-5 days", loaded.getStrategyState().getHorizon());
        assertEquals(decisionId, loaded.getStrategyState().getSourceDecisionId());
    }

    @Test
    void readsOldStateJsonWithoutRiskState() throws Exception {
        Path stateFile = tempDir.resolve("old-trading-state.json");
        Files.writeString(stateFile, """
                {
                  "trackedBaseAmount": 0.1,
                  "averageCost": 50000,
                  "updatedAt": "2026-05-17T00:00:00Z",
                  "recentDecisions": []
                }
                """);

        TradingState loaded = new TradingStateRepository(stateFile).getState();

        assertDecimal("0.1", loaded.getTrackedBaseAmount());
        assertDecimal("50000", loaded.getAverageCost());
        assertNotNull(loaded.getRiskState());
        assertDecimal("0", loaded.getRiskState().getCurrentEquity());
    }

    @Test
    void importsLegacyFinancialValuesOnceAndRewritesOnlyStrategyMemory() throws Exception {
        Path stateFile = tempDir.resolve("legacy-financial-state.json");
        Files.writeString(stateFile, """
                {
                  "trackedBaseAmount": 0.25,
                  "averageCost": 42000,
                  "riskState": {"currentEquity": 900},
                  "recentDecisions": []
                }
                """);
        InMemoryTradingFinancialStateStore financialStore = new InMemoryTradingFinancialStateStore();
        TradingStateRepository repository = repository(stateFile, financialStore);

        assertDecimal("0.25", repository.getState().getTrackedBaseAmount());
        assertDecimal("900", repository.getState().getRiskState().getCurrentEquity());
        repository.recordDecision(decision("1", TradingAction.HOLD), 2);

        String rewritten = Files.readString(stateFile);
        assertFalse(rewritten.contains("trackedBaseAmount"));
        assertFalse(rewritten.contains("averageCost"));
        assertFalse(rewritten.contains("riskState"));
        assertDecimal("0.25", repository(stateFile, financialStore).getState().getTrackedBaseAmount());
    }

    @Test
    void preservesRiskStateAcrossRepositoryMutations() {
        Path stateFile = tempDir.resolve("risk-state-preserved.json");
        InMemoryTradingFinancialStateStore financialStore = new InMemoryTradingFinancialStateStore();
        TradingStateRepository repository = repository(stateFile, financialStore);
        TradingRiskState riskState = new TradingRiskState()
                .setCurrentEquity(new BigDecimal("1000"))
                .setEquityHighWatermark(new BigDecimal("1200"))
                .setDayStartEquity(new BigDecimal("1100"))
                .setDayStartDate("2026-05-17")
                .setConsecutiveLosses(2)
                .setLossCooldownUntil("2026-05-17T03:00:00Z")
                .setLastTradeTime("2026-05-17T02:00:00Z")
                .setConsecutiveOpenActions(1)
                .setLastRiskReason("RISK_TEST");

        repository.recordRiskState(riskState);
        repository.recordBuy(new BigDecimal("0.1"), new BigDecimal("50000"));
        assertRiskStatePreserved(repository(stateFile, financialStore).getState().getRiskState());

        repository.recordSell(new BigDecimal("0.05"));
        assertRiskStatePreserved(repository(stateFile, financialStore).getState().getRiskState());

        repository.recordDecision(decision("1", TradingAction.HOLD), 2);
        assertRiskStatePreserved(repository(stateFile, financialStore).getState().getRiskState());

        repository.recordStrategyState("2", new AiTradingDecision()
                .setStrategyBias("LONG")
                .setStrategyThesis("trend continuation"));
        assertRiskStatePreserved(repository(stateFile, financialStore).getState().getRiskState());
    }

    @Test
    void persistsActiveStrategyAndRejectsStaleOperatorRevision() {
        Path stateFile = tempDir.resolve("active-strategy.json");
        TradingStateRepository repository = new TradingStateRepository(stateFile);

        TradingState first = repository.selectActiveStrategy("balanced", 0L);
        assertEquals("balanced", first.getActiveStrategyId());
        assertEquals(1L, first.getActiveStrategyRevision());
        assertNotNull(first.getActiveStrategyChangedAt());

        repository.recordDecision(decision("1", TradingAction.HOLD), 2);
        TradingState reloaded = new TradingStateRepository(stateFile).getState();
        assertEquals("balanced", reloaded.getActiveStrategyId());
        assertEquals(1L, reloaded.getActiveStrategyRevision());

        assertThrows(ConcurrentModificationException.class,
                () -> repository.selectActiveStrategy("defensive", 0L));
    }

    @Test
    void concurrentMemoryWritersPreserveLegacySeedsAndEachCommittedUpdate() throws Exception {
        Path file = tempDir.resolve("concurrent-legacy.json");
        Files.writeString(file, """
                {"trackedBaseAmount":0.25,"averageCost":42000,
                 "riskState":{"currentEquity":900},"recentDecisions":[]}
                """);
        var financialStore = new InMemoryTradingFinancialStateStore();
        var repository = repository(file, financialStore);
        var ready = new CountDownLatch(1);
        var writes = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 16; i++) {
            String id = "decision-" + i;
            writes.add(() -> {
                ready.await();
                repository.recordDecision(decision(id, TradingAction.HOLD), 32);
                return true;
            });
        }
        writes.add(() -> {
            ready.await();
            repository.recordStrategyState("thesis", new AiTradingDecision().setStrategyThesis("preserved"));
            return true;
        });
        for (String strategy : java.util.List.of("balanced", "defensive")) {
            writes.add(() -> {
                ready.await();
                try {
                    repository.selectActiveStrategy(strategy, 0L);
                    return true;
                } catch (ConcurrentModificationException expected) {
                    return false;
                }
            });
        }
        var executor = Executors.newFixedThreadPool(6);
        try {
            var futures = writes.stream().map(executor::submit).toList();
            ready.countDown();
            int successes = 0;
            for (var future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) successes++;
            }
            assertEquals(18, successes); // Sixteen records, one thesis and one revision winner.
        } finally {
            executor.shutdownNow();
        }

        TradingState restored = repository(file, financialStore).getState();
        assertDecimal("0.25", restored.getTrackedBaseAmount());
        assertDecimal("42000", restored.getAverageCost());
        assertDecimal("900", restored.getRiskState().getCurrentEquity());
        assertEquals(16, restored.getRecentDecisions().size());
        assertEquals(16, restored.getRecentDecisions().stream().map(TradingDecisionRecord::getTimestamp).distinct().count());
        assertEquals("preserved", restored.getStrategyState().getThesis());
        assertEquals(1L, restored.getActiveStrategyRevision());
        assertTrue(java.util.List.of("balanced", "defensive").contains(restored.getActiveStrategyId()));
        assertFalse(Files.readString(file).contains("trackedBaseAmount"));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static TradingStateRepository repository(
            Path stateFile,
            TradingFinancialStateStore financialStore
    ) {
        return new TradingStateRepository(stateFile, financialStore, "test", "BTC-USDT");
    }

    private static TradingDecisionRecord decision(String timestamp, TradingAction action) {
        return new TradingDecisionRecord()
                .setTimestamp(timestamp)
                .setAction(action)
                .setReason("test")
                .setExecutionStatus("FILLED");
    }

    private static void assertRiskStatePreserved(TradingRiskState riskState) {
        assertNotNull(riskState);
        assertDecimal("1000", riskState.getCurrentEquity());
        assertDecimal("1200", riskState.getEquityHighWatermark());
        assertDecimal("1100", riskState.getDayStartEquity());
        assertEquals("2026-05-17", riskState.getDayStartDate());
        assertEquals(2, riskState.getConsecutiveLosses());
        assertEquals("2026-05-17T03:00:00Z", riskState.getLossCooldownUntil());
        assertEquals("2026-05-17T02:00:00Z", riskState.getLastTradeTime());
        assertEquals(1, riskState.getConsecutiveOpenActions());
        assertEquals("RISK_TEST", riskState.getLastRiskReason());
    }
}
