package com.trade.trading.domain.strategy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic long-only setups; stops and targets are evaluated at candle close. */
public final class IntradayTechnicalPolicy {
    public enum Mode { TREND_PULLBACK, VOLUME_BREAKOUT, RANGE_REVERSION }
    public enum Action { ENTER, EXIT, HOLD }
    public record Settings(Mode mode, double trendMinAdx, double rangeMaxAdx, double entryRsiMin,
                           double entryRsiMax, double minVolumeRatio, double minAtrPercent,
                           double maxAtrPercent, double maxEntryExtensionAtr, double stopLossPercent,
                           double takeProfitPercent, double minNetRewardPercent) {}
    public record Result(Action action, String reason, Map<String, Object> metadata) {}

    public Result evaluate(TechnicalIndicators.Snapshot s, Settings c, boolean hasPosition,
                           double averageCost, double roundTripCost, double minRewardRisk,
                           double minimumNetEdge) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", c.mode().name());
        m.put("close", s.close());
        m.put("emaFast", s.fastEma());
        m.put("emaSlow", s.slowEma());
        m.put("rsi", s.rsi());
        m.put("atr", s.atr());
        m.put("atrPercent", s.atr() / s.close());
        m.put("adx", s.adx());
        m.put("bollingerMiddle", s.bollingerMiddle());
        m.put("bollingerUpper", s.bollingerUpper());
        m.put("bollingerLower", s.bollingerLower());
        m.put("donchianHigh", s.donchianHigh());
        m.put("volumeRatio", s.volumeRatio());
        m.put("estimatedRoundTripCostPercent", roundTripCost);
        if (hasPosition) {
            if (!(averageCost > 0) || !Double.isFinite(averageCost)) {
                return result(Action.HOLD, "Managed position cost is unavailable", m);
            }
            double pnl = s.close() / averageCost - 1;
            m.put("positionReturnPercent", pnl);
            m.put("stopPrice", averageCost * (1 - c.stopLossPercent()));
            m.put("takeProfitPrice", averageCost * (1 + c.takeProfitPercent()));
            if (pnl <= -c.stopLossPercent()) return result(Action.EXIT, "Closed-candle stop loss reached", m);
            if (pnl >= c.takeProfitPercent()) return result(Action.EXIT, "Closed-candle profit target reached", m);
            if (c.mode() == Mode.RANGE_REVERSION && s.close() >= s.bollingerMiddle()) {
                return result(Action.EXIT, "Price reverted to Bollinger middle", m);
            }
            if (c.mode() != Mode.RANGE_REVERSION && s.close() < s.slowEma()) {
                return result(Action.EXIT, "Price closed below slow EMA", m);
            }
            return result(Action.HOLD, "Managed position open; no pyramiding", m);
        }

        double atrPercent = s.atr() / s.close();
        if (atrPercent < c.minAtrPercent() || atrPercent > c.maxAtrPercent()) {
            return result(Action.HOLD, "ATR volatility outside entry range", m);
        }
        if (s.volumeRatio() < c.minVolumeRatio()) return result(Action.HOLD, "Volume confirmation not met", m);
        if (s.rsi() < c.entryRsiMin() || s.rsi() > c.entryRsiMax()) {
            return result(Action.HOLD, "RSI outside entry range", m);
        }
        boolean signal = switch (c.mode()) {
            case TREND_PULLBACK -> s.fastEma() > s.slowEma() && s.adx() >= c.trendMinAdx()
                    && s.previousClose() <= s.previousFastEma() && s.close() > s.fastEma()
                    && s.rsi() > s.previousRsi();
            case VOLUME_BREAKOUT -> s.fastEma() > s.slowEma() && s.adx() >= c.trendMinAdx()
                    && s.close() > s.donchianHigh();
            case RANGE_REVERSION -> s.adx() <= c.rangeMaxAdx()
                    && s.previousClose() <= s.previousBollingerLower() && s.close() > s.bollingerLower()
                    && s.close() < s.bollingerMiddle() && s.rsi() > s.previousRsi();
        };
        if (!signal) return result(Action.HOLD, "Setup confirmation not met: " + c.mode(), m);
        if (c.mode() != Mode.RANGE_REVERSION && s.close() - s.fastEma() > c.maxEntryExtensionAtr() * s.atr()) {
            return result(Action.HOLD, "Price too extended from fast EMA", m);
        }
        double reward = c.takeProfitPercent();
        if (c.mode() == Mode.RANGE_REVERSION) reward = Math.min(reward, s.bollingerMiddle() / s.close() - 1);
        double netReward = reward - roundTripCost;
        double rewardRisk = netReward / (c.stopLossPercent() + roundTripCost);
        m.put("plannedGrossRewardPercent", reward);
        m.put("plannedNetRewardPercent", netReward);
        m.put("plannedNetRewardRiskRatio", rewardRisk);
        if (netReward < Math.max(c.minNetRewardPercent(), minimumNetEdge) || rewardRisk < minRewardRisk) {
            return result(Action.HOLD, "Planned reward insufficient after estimated fees and slippage", m);
        }
        return result(Action.ENTER, "Confirmed " + c.mode() + " setup", m);
    }

    private static Result result(Action action, String reason, Map<String, Object> metadata) {
        return new Result(action, reason, Map.copyOf(metadata));
    }
}
