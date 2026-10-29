package com.trade.trading.domain.risk;

import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingRiskState;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class RiskStateTransitionsTest {
    private final RiskPolicy policy = new RiskPolicy(true, 2, 60_000, new BigDecimal("0.2"),
            new BigDecimal("0.05"), "Asia/Shanghai", 1000, 2, new BigDecimal("0.1"),
            new BigDecimal("0.001"));
    private final RiskStateTransitions transitions = new RiskStateTransitions(policy);

    @Test
    void dayBoundaryResetsDailyReferenceWithoutResettingDrawdownOrMutatingSource() {
        TradingRiskState source = new TradingRiskState().setCurrentEquity(new BigDecimal("100"))
                .setEquityHighWatermark(new BigDecimal("120")).setDayStartEquity(new BigDecimal("110"))
                .setDayStartDate("2026-09-26").setConsecutiveReconciliationFailures(3)
                .setLastReconciliationError("unavailable");
        TradingRiskState result = transitions.refresh(source, new BigDecimal("95"),
                Instant.parse("2026-09-26T16:00:00Z"));
        assertEquals("2026-09-27", result.getDayStartDate());
        assertEquals(new BigDecimal("95"), result.getDayStartEquity());
        assertEquals(new BigDecimal("120"), result.getEquityHighWatermark());
        assertEquals(3, result.getConsecutiveReconciliationFailures());
        assertEquals("unavailable", result.getLastReconciliationError());
        assertEquals(new BigDecimal("100"), source.getCurrentEquity());
        assertEquals("2026-09-26", source.getDayStartDate());
    }

    @Test
    void closedLosingTradesTriggerCooldownAndOnlyFullRoundTripsCount() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        TradingRiskState source = new TradingRiskState().setCurrentEquity(new BigDecimal("100"));
        source = transitions.filled(source, TradingAction.BUY, BigDecimal.ZERO, true, false, BigDecimal.ZERO, now, true);
        source = transitions.filled(source, TradingAction.SELL, BigDecimal.ZERO, false, false, new BigDecimal("-10"), now, true);
        assertEquals(0, source.getConsecutiveLosses());
        source = transitions.filled(source, TradingAction.SELL, BigDecimal.ZERO, false, true, new BigDecimal("5"), now, false);
        assertEquals(1, source.getConsecutiveLosses());
        source = transitions.filled(source, TradingAction.BUY, BigDecimal.ZERO, true, false, BigDecimal.ZERO, now, true);
        TradingRiskState blocked = transitions.filled(source, TradingAction.SELL, BigDecimal.ZERO,
                false, true, new BigDecimal("-1"), now, true);
        assertEquals(now.plusSeconds(60).toString(), blocked.getLossCooldownUntil());
        TradingRiskState continued = transitions.refresh(blocked, new BigDecimal("80"), now.plusSeconds(30));
        assertEquals(blocked.getLossCooldownUntil(), continued.getLossCooldownUntil());
        TradingRiskState expired = transitions.refresh(continued, new BigDecimal("80"), now.plusSeconds(60));
        assertNull(expired.getLossCooldownUntil());
        assertEquals(0, expired.getConsecutiveLosses());
    }

    @Test
    void sampledEquityDeclinesAndLegacyLossStreaksNeverCountAsClosedLosses() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        TradingRiskState state = new TradingRiskState().setCurrentEquity(new BigDecimal("100"))
                .setConsecutiveLosses(99).setLossCooldownUntil(now.plusSeconds(3600).toString());
        for (int i = 1; i <= 5; i++) state = transitions.refresh(state, BigDecimal.valueOf(100 - i), now.plusSeconds(i));
        assertEquals(0, state.getConsecutiveLosses());
        assertNull(state.getLossCooldownUntil());
        // A legacy position has no reliable opening boundary; its first close is not fabricated as a full trade.
        state = transitions.filled(state, TradingAction.SELL, BigDecimal.ZERO, false, true, new BigDecimal("-100"), now, true);
        assertEquals(0, state.getConsecutiveLosses());
    }

    @Test
    void confirmedCloseResetsOpenCounterButPreservesEquityWhenNoNewEstimateExists() {
        TradingRiskState source = new TradingRiskState().setCurrentEquity(new BigDecimal("100"))
                .setConsecutiveOpenActions(2);
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        TradingRiskState result = transitions.executed(source, TradingAction.SELL, BigDecimal.ZERO, now);
        assertEquals(0, result.getConsecutiveOpenActions());
        assertEquals(new BigDecimal("100"), result.getCurrentEquity());
        assertEquals(now.toString(), result.getLastTradeTime());
        assertEquals(2, source.getConsecutiveOpenActions());
    }

    @Test
    void lateExecutedActionCannotRegressTheCommittedTradeTime() {
        Instant latest = Instant.parse("2026-09-27T00:15:00Z");
        TradingRiskState source = new TradingRiskState().setLastTradeTime(latest.toString())
                .setConsecutiveOpenActions(2);
        TradingRiskState lateClose = transitions.executed(source, TradingAction.SELL, BigDecimal.ZERO,
                latest.minusSeconds(60));
        assertEquals(latest.toString(), lateClose.getLastTradeTime());
        assertEquals(0, lateClose.getConsecutiveOpenActions());
        assertEquals(2, source.getConsecutiveOpenActions());
        assertEquals(latest.plusSeconds(60).toString(), transitions.executed(lateClose,
                TradingAction.BUY, BigDecimal.ZERO, latest.plusSeconds(60)).getLastTradeTime());
    }
}
