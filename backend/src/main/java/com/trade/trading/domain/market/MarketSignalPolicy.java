package com.trade.trading.domain.market;

import com.trade.common.support.TradingMath;
import com.trade.trading.domain.model.MarketSignal;
import com.trade.trading.domain.model.TradingState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts raw market snapshots into lightweight triggers for the scheduler.
 * Detection is intentionally stateless except for local position data supplied
 * by {@link TradingState}.
 */
public class MarketSignalPolicy {
    public record Candle(BigDecimal close, BigDecimal quoteVolume, boolean confirmed) {}
    public record Thresholds(BigDecimal priceMoveTriggerPercent, BigDecimal volumeSpikeMultiplier,
                             BigDecimal floatingLossTriggerPercent) {}

    private final Thresholds properties;

    public MarketSignalPolicy(Thresholds properties) {
        this.properties = properties;
    }

    public List<MarketSignal> detect(BigDecimal lastPrice, List<Candle> oneMinuteCandles, TradingState state) {
        List<MarketSignal> events = new ArrayList<>();
        // OKX may include the still-forming current candle; trigger logic uses
        // only confirmed candles to avoid reacting to incomplete volume/price.
        List<Candle> confirmedCandles = confirmed(oneMinuteCandles);

        detectPriceMove(events, lastPrice, confirmedCandles);
        detectVolumeSpike(events, confirmedCandles);
        detectFloatingLoss(events, lastPrice, state);

        return events;
    }

    private void detectPriceMove(List<MarketSignal> events, BigDecimal lastPrice, List<Candle> confirmedCandles) {
        if (lastPrice.signum() <= 0 || confirmedCandles.size() < 5) {
            return;
        }

        BigDecimal fiveMinuteBase = confirmedCandles.get(4).close();
        BigDecimal change = TradingMath.percentChange(lastPrice, fiveMinuteBase);
        if (change.abs().compareTo(properties.priceMoveTriggerPercent()) >= 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lastPrice", lastPrice);
            details.put("fiveMinuteBasePrice", fiveMinuteBase);
            details.put("changePercent", change);
            details.put("thresholdPercent", properties.priceMoveTriggerPercent());
            events.add(new MarketSignal("PRICE_MOVE_5M", "5 minute price move threshold reached", details));
        }
    }

    private void detectVolumeSpike(List<MarketSignal> events, List<Candle> confirmedCandles) {
        if (confirmedCandles.size() < 21) {
            return;
        }

        BigDecimal latestVolume = confirmedCandles.get(0).quoteVolume();
        BigDecimal previousSum = BigDecimal.ZERO;
        for (int i = 1; i <= 20; i++) {
            previousSum = previousSum.add(confirmedCandles.get(i).quoteVolume());
        }
        BigDecimal average = previousSum.divide(new BigDecimal("20"), 10, java.math.RoundingMode.HALF_UP);
        if (average.signum() <= 0) {
            return;
        }

        BigDecimal ratio = latestVolume.divide(average, 10, RoundingMode.HALF_UP);
        if (ratio.compareTo(properties.volumeSpikeMultiplier()) >= 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("latestOneMinuteQuoteVolume", latestVolume);
            details.put("previousTwentyAverageQuoteVolume", average);
            details.put("ratio", ratio);
            details.put("thresholdMultiplier", properties.volumeSpikeMultiplier());
            events.add(new MarketSignal("VOLUME_SPIKE", "1 minute quote volume spike threshold reached", details));
        }
    }

    private void detectFloatingLoss(List<MarketSignal> events, BigDecimal lastPrice, TradingState state) {
        if (state == null || !state.hasTrackedPosition() || lastPrice.signum() <= 0) {
            return;
        }

        BigDecimal pnlPercent = TradingMath.percentChange(lastPrice, state.getAverageCost());
        BigDecimal lossThreshold = properties.floatingLossTriggerPercent().negate();
        if (pnlPercent.compareTo(lossThreshold) <= 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lastPrice", lastPrice);
            details.put("averageCost", state.getAverageCost());
            details.put("trackedBaseAmount", state.getTrackedBaseAmount());
            details.put("unrealizedPnlPercent", pnlPercent);
            details.put("lossThresholdPercent", lossThreshold);
            events.add(new MarketSignal("FLOATING_LOSS", "Tracked position floating loss threshold reached", details));
        }
    }

    private static List<Candle> confirmed(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }

        List<Candle> result = new ArrayList<>();
        for (Candle candle : candles) {
            if (candle.confirmed()) {
                result.add(candle);
            }
        }
        return result;
    }
}
