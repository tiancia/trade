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
    void activeCooldownIsNotExtendedByFurtherLossesAndExpiresAtExactBoundary() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        TradingRiskState source = new TradingRiskState().setCurrentEquity(new BigDecimal("100"))
                .setConsecutiveLosses(1);
        TradingRiskState blocked = transitions.refresh(source, new BigDecimal("90"), now);
        assertEquals(now.plusSeconds(60).toString(), blocked.getLossCooldownUntil());
        TradingRiskState continued = transitions.refresh(blocked, new BigDecimal("80"), now.plusSeconds(30));
        assertEquals(blocked.getLossCooldownUntil(), continued.getLossCooldownUntil());
        TradingRiskState expired = transitions.refresh(continued, new BigDecimal("80"), now.plusSeconds(60));
        assertNull(expired.getLossCooldownUntil());
        assertEquals(0, expired.getConsecutiveLosses());
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
}
