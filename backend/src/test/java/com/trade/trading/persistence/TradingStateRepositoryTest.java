package com.trade.trading.persistence;

import com.trade.trading.model.TradingAction;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.TradingDecisionRecord;
import com.trade.trading.model.TradingRiskState;
import com.trade.trading.model.TradingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void preservesRiskStateAcrossRepositoryMutations() {
        Path stateFile = tempDir.resolve("risk-state-preserved.json");
        TradingStateRepository repository = new TradingStateRepository(stateFile);
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
        assertRiskStatePreserved(new TradingStateRepository(stateFile).getState().getRiskState());

        repository.recordSell(new BigDecimal("0.05"));
        assertRiskStatePreserved(new TradingStateRepository(stateFile).getState().getRiskState());

        repository.recordDecision(decision("1", TradingAction.HOLD), 2);
        assertRiskStatePreserved(new TradingStateRepository(stateFile).getState().getRiskState());

        repository.recordStrategyState("2", new AiTradingDecision()
                .setStrategyBias("LONG")
                .setStrategyThesis("trend continuation"));
        assertRiskStatePreserved(new TradingStateRepository(stateFile).getState().getRiskState());
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
