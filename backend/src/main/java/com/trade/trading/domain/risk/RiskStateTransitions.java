package com.trade.trading.domain.risk;

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
        if (equity.signum() <= 0) {
            return state;
        }
        applyDailyBoundary(state, equity, now);
        applyEquityLossState(state, equity, now);
        updateEquity(state, equity);
        return state;
    }

    public TradingRiskState executed(TradingRiskState source, TradingAction action,
                                     BigDecimal equity, Instant now) {
        TradingRiskState state = copyRiskState(source);
        updateEquity(state, equity);
        state.setLastTradeTime(now.toString());
        if (action.isOpenAction()) {
            state.setConsecutiveOpenActions(state.getConsecutiveOpenActions() + 1);
        } else if (action.isCloseAction()) {
            state.setConsecutiveOpenActions(0);
        }
        return state;
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

    private void applyEquityLossState(TradingRiskState riskState, BigDecimal currentEquity, Instant now) {
        RiskPolicy riskProperties = policy;
        BigDecimal previousEquity = zeroIfNull(riskState.getCurrentEquity());
        if (previousEquity.signum() <= 0) {
            riskState.setConsecutiveLosses(Math.max(0, riskState.getConsecutiveLosses()));
            return;
        }

        BigDecimal noise = previousEquity.multiply(zeroIfNull(riskProperties.getEquityNoiseRatio()));
        BigDecimal decline = previousEquity.subtract(currentEquity);
        boolean hasLoss = decline.compareTo(noise) > 0;
        Instant cooldownUntil = parseInstant(riskState.getLossCooldownUntil());
        boolean activeCooldown = cooldownUntil != null && now.isBefore(cooldownUntil);
        // Tiny equity movements below the noise ratio do not count as losses;
        // this prevents fees or mark-price jitter from triggering cooldowns.
        if (hasLoss) {
            riskState.setConsecutiveLosses(riskState.getConsecutiveLosses() + 1);
        } else if (!activeCooldown) {
            riskState.setConsecutiveLosses(0);
        }

        if (hasLoss
                && riskProperties.getMaxConsecutiveLosses() > 0
                && riskState.getConsecutiveLosses() >= riskProperties.getMaxConsecutiveLosses()
                && !activeCooldown) {
            riskState.setLossCooldownUntil(now.plusMillis(riskProperties.getLossCooldownMs()).toString());
        } else if (!activeCooldown) {
            riskState.setLossCooldownUntil(null);
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
                .setLossCooldownUntil(source.getLossCooldownUntil())
                .setLastTradeTime(source.getLastTradeTime())
                .setConsecutiveOpenActions(source.getConsecutiveOpenActions())
                .setLastRiskReason(source.getLastRiskReason())
                .setConsecutiveReconciliationFailures(source.getConsecutiveReconciliationFailures())
                .setLastReconciliationAt(source.getLastReconciliationAt())
                .setLastReconciliationError(source.getLastReconciliationError());
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
