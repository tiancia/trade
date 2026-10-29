package com.trade.trading.domain.risk;

import static com.trade.trading.domain.model.TradingStateTimestamps.parseOrNull;

import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingRiskState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Pure risk-day, drawdown, cooldown, and executed-action state transitions. */
public final class RiskStateTransitions {
    private final RiskPolicy policy;

    public RiskStateTransitions(RiskPolicy policy) {
        this.policy = policy;
    }

    public TradingRiskState refresh(TradingRiskState source, BigDecimal equity, Instant now) {
        TradingRiskState state = copyRiskState(source);
        initializeClosedTradeAccounting(state);
        Instant cooldown = parseOrNull(state.getLossCooldownUntil());
        if (cooldown != null && !now.isBefore(cooldown)) {
            state.setLossCooldownUntil(null).setConsecutiveLosses(0);
        }
        if (equity.signum() <= 0) {
            return state;
        }
        applyDailyBoundary(state, equity, now);
        updateEquity(state, equity);
        return state;
    }

    public TradingRiskState executed(TradingRiskState source, TradingAction action,
                                     BigDecimal equity, Instant now) {
        TradingRiskState state = copyRiskState(source);
        updateEquity(state, equity);
        state.setLastTradeTime(latestTradeTime(state.getLastTradeTime(), now));
        if (action.isOpenAction()) {
            state.setConsecutiveOpenActions(state.getConsecutiveOpenActions() + 1);
        } else if (action.isCloseAction()) {
            state.setConsecutiveOpenActions(0);
        }
        return state;
    }

    /** A late fill or secondary risk update cannot move a committed trade time backwards. */
    public static String latestTradeTime(String previous, Instant occurredAt) {
        Instant previousTime = parseOrNull(previous);
        return occurredAt == null || (previousTime != null && previousTime.isAfter(occurredAt))
                ? previous : occurredAt.toString();
    }

    private static void updateEquity(TradingRiskState state, BigDecimal equity) {
        if (equity.signum() > 0) {
            state.setCurrentEquity(equity);
            if (zeroIfNull(state.getEquityHighWatermark()).compareTo(equity) < 0) {
                state.setEquityHighWatermark(equity);
            }
        }
    }

    private void applyDailyBoundary(TradingRiskState riskState, BigDecimal currentEquity, Instant now) {
        String today = LocalDate.ofInstant(now, dailyZone()).toString();
        // A new risk day resets the reference equity used by daily loss checks.
        if (riskState.getDayStartDate() == null
                || !riskState.getDayStartDate().equals(today)
                || zeroIfNull(riskState.getDayStartEquity()).signum() <= 0) {
            riskState.setDayStartDate(today)
                    .setDayStartEquity(currentEquity);
        }
    }

    /** Called exactly once per advancing fill, inside the monetary transaction. */
    public TradingRiskState filled(TradingRiskState source, TradingAction action, BigDecimal equity,
                                   boolean openedPosition, boolean closedPosition, BigDecimal realizedPnlDelta,
                                   Instant now, boolean firstOrderFill) {
        TradingRiskState state = copyRiskState(source);
        initializeClosedTradeAccounting(state);
        if (policy.isEnabled() && firstOrderFill) state = executed(state, action, equity, now);
        else {
            updateEquity(state, equity);
            state.setLastTradeTime(latestTradeTime(state.getLastTradeTime(), now));
        }
        if (openedPosition) state.setOpenTradeRealizedPnl(BigDecimal.ZERO);
        if (action.isCloseAction() && state.getOpenTradeRealizedPnl() != null) {
            state.setOpenTradeRealizedPnl(state.getOpenTradeRealizedPnl().add(zeroIfNull(realizedPnlDelta)));
            if (closedPosition) {
                if (policy.isEnabled()) {
                    boolean loss = state.getOpenTradeRealizedPnl().signum() < 0;
                    state.setConsecutiveLosses(loss ? state.getConsecutiveLosses() + 1 : 0);
                    Instant until = parseOrNull(state.getLossCooldownUntil());
                    if (loss && policy.getMaxConsecutiveLosses() > 0
                            && state.getConsecutiveLosses() >= policy.getMaxConsecutiveLosses()
                            && (until == null || !now.isBefore(until))) {
                        state.setLossCooldownUntil(now.plusMillis(policy.getLossCooldownMs()).toString());
                    }
                }
                state.setOpenTradeRealizedPnl(null);
            }
        }
        return state;
    }

    private static void initializeClosedTradeAccounting(TradingRiskState state) {
        if (state.getClosedTradeAccountingVersion() == 0) {
            // Old values counted sampled equity declines, not closed trades.
            // An already-open legacy position is untracked until the next flat entry.
            state.setConsecutiveLosses(0).setLossCooldownUntil(null).setOpenTradeRealizedPnl(null)
                    .setClosedTradeAccountingVersion(1);
        }
    }

    private ZoneId dailyZone() {
        String zone = policy.getDailyZone();
        if (zone == null || zone.isBlank()) {
            return ZoneId.of("Asia/Shanghai");
        }
        return ZoneId.of(zone);
    }

    public static TradingRiskState copyRiskState(TradingRiskState source) {
        if (source == null) {
            return new TradingRiskState();
        }
        return new TradingRiskState()
                .setCurrentEquity(zeroIfNull(source.getCurrentEquity()))
                .setEquityHighWatermark(zeroIfNull(source.getEquityHighWatermark()))
                .setDayStartEquity(zeroIfNull(source.getDayStartEquity()))
                .setDayStartDate(source.getDayStartDate())
                .setConsecutiveLosses(source.getConsecutiveLosses())
                .setOpenTradeRealizedPnl(source.getOpenTradeRealizedPnl())
                .setClosedTradeAccountingVersion(source.getClosedTradeAccountingVersion())
                .setLossCooldownUntil(source.getLossCooldownUntil())
                .setLastTradeTime(source.getLastTradeTime())
                .setConsecutiveOpenActions(source.getConsecutiveOpenActions())
                .setLastRiskReason(source.getLastRiskReason())
                .setConsecutiveReconciliationFailures(source.getConsecutiveReconciliationFailures())
                .setLastReconciliationAt(source.getLastReconciliationAt())
                .setLastReconciliationError(source.getLastReconciliationError());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
