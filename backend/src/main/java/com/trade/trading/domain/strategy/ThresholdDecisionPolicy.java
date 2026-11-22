package com.trade.trading.domain.strategy;

import com.trade.common.support.TradingMath;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingState;
import lombok.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Price/volume entry and position-loss exit rules, independent of transport and configuration. */
public final class ThresholdDecisionPolicy {
    public record Candle(BigDecimal close, BigDecimal quoteVolume, BigDecimal volume, boolean confirmed) {}

    @Value
    public static class Facts {
        String strategyId;
        String bar;
        List<Candle> candles;
        TradingState tradingState;
        BigDecimal availableBase;
        boolean derivativeInstrument;
        BigDecimal maxBuyQuoteAmount;
        BigDecimal maxDerivativeOrderSize;
    }

    @Value
    public static class Settings {
        BigDecimal priceMoveTriggerPercent;
        BigDecimal volumeSpikeMultiplier;
        BigDecimal floatingLossTriggerPercent;
        BigDecimal buyQuoteAmount;
        BigDecimal sellBaseAmount;
        BigDecimal orderSize;
        int priceMoveWindowCandles;
        int volumeLookbackCandles;
        boolean requireConfirmedCandle;
    }

    public StrategyDecision evaluate(Facts context, Settings config) {
        List<Candle> candles = usableCandles(context.getCandles(), config.isRequireConfirmedCandle());
        if (candles.isEmpty()) {
            return StrategyDecision.hold(context.getStrategyId(), "No usable candles available");
        }
        // Missing/malformed provider prices can arrive as zero from the input
        // adapter. They cannot establish a price collapse or a stop-loss exit.
        if (!hasValidClose(candles.getFirst())) {
            return StrategyDecision.hold(context.getStrategyId(), "Invalid latest candle close; threshold decision deferred");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        BigDecimal change = priceMove(candles, config.getPriceMoveWindowCandles());
        BigDecimal volumeRatio = volumeRatio(candles, config.getVolumeLookbackCandles());
        TradingState state = context.getTradingState();
        BigDecimal positionReturn = state != null && state.hasTrackedPosition()
                ? TradingMath.percentChange(candles.getFirst().close(), state.getAverageCost()) : null;
        metadata.put("bar", context.getBar());
        metadata.put("priceMoveThresholdPercent", config.getPriceMoveTriggerPercent());
        metadata.put("volumeSpikeThresholdMultiplier", config.getVolumeSpikeMultiplier());
        metadata.put("floatingLossThresholdPercent", config.getFloatingLossTriggerPercent());
        if (change != null) metadata.put("priceMovePercent", change);
        if (volumeRatio != null) metadata.put("volumeSpikeRatio", volumeRatio);
        if (positionReturn != null) metadata.put("floatingLossPercent", positionReturn);

        if (positionReturn != null && positionReturn.compareTo(config.getFloatingLossTriggerPercent().negate()) <= 0) {
            return exitDecision(context, config, metadata, "Tracked position floating loss threshold reached");
        }
        if (change != null && hasPosition(context) && change.compareTo(config.getPriceMoveTriggerPercent().negate()) <= 0) {
            return exitDecision(context, config, metadata, "Reverse price move threshold reached");
        }
        if (change == null || volumeRatio == null) {
            return StrategyDecision.hold(context.getStrategyId(), "Insufficient valid price or volume history for threshold entry")
                    .setMetadata(metadata);
        }
        if (change.compareTo(config.getPriceMoveTriggerPercent()) >= 0
                && volumeRatio.compareTo(config.getVolumeSpikeMultiplier()) >= 0) {
            return enterDecision(context, config, metadata);
        }
        return StrategyDecision.hold(context.getStrategyId(), "Threshold conditions not met")
                .setMetadata(metadata);
    }

    private static StrategyDecision enterDecision(
            Facts context,
            Settings config,
            Map<String, Object> metadata
    ) {

        BigDecimal buyQuoteAmount = firstPositive(config.getBuyQuoteAmount(), context.getMaxBuyQuoteAmount());
        BigDecimal orderSize = firstPositive(config.getOrderSize(), context.getMaxDerivativeOrderSize());
        String reason = "Positive price move and volume spike thresholds reached";
        StrategyDecision decision = context.isDerivativeInstrument()
                ? StrategyDecision.openLong(context.getStrategyId(), orderSize, reason)
                        .setBuyQuoteAmount(buyQuoteAmount)
                : StrategyDecision.buy(context.getStrategyId(), buyQuoteAmount, reason)
                        .setOrderSize(orderSize);
        return decision.setMetadata(metadata);
    }

    private static StrategyDecision exitDecision(
            Facts context,
            Settings config,
            Map<String, Object> metadata,
            String reason
    ) {

        BigDecimal owned = exitBaseAmount(context);
        BigDecimal sellBaseAmount = firstPositive(config.getSellBaseAmount(), owned).min(owned);
        BigDecimal orderSize = firstPositive(config.getOrderSize(), context.getMaxDerivativeOrderSize());
        StrategyDecision decision = context.isDerivativeInstrument()
                ? StrategyDecision.closeLong(context.getStrategyId(), orderSize, reason)
                        .setSellBaseAmount(sellBaseAmount)
                : StrategyDecision.sell(context.getStrategyId(), sellBaseAmount, reason)
                        .setOrderSize(orderSize);
        return decision.setMetadata(metadata);
    }

    private static BigDecimal priceMove(List<Candle> confirmed, int windowCandles) {
        int window = Math.max(windowCandles, 2);
        if (confirmed.size() < window) {
            return null;
        }
        // Do not infer a move across a broken window. A verified latest-price
        // loss exit remains available independently of historical comparisons.
        if (confirmed.subList(0, window).stream().anyMatch(candle -> !hasValidClose(candle))) {
            return null;
        }
        BigDecimal current = confirmed.getFirst().close();
        BigDecimal base = confirmed.get(window - 1).close();
        return TradingMath.percentChange(current, base);
    }

    private static boolean hasValidClose(Candle candle) {
        return candle.close() != null && candle.close().signum() > 0;
    }

    private static BigDecimal volumeRatio(List<Candle> candles, int lookbackCandles) {
        int lookback = Math.max(lookbackCandles, 1);
        if (candles.size() <= lookback) return null;
        List<Candle> window = candles.subList(0, lookback + 1);
        // Both sides of a ratio must use the same volume field, including when only fallback data is available.
        Function<Candle, BigDecimal> field = window.stream().allMatch(c -> validVolume(c.quoteVolume()))
                ? Candle::quoteVolume : Candle::volume;
        BigDecimal latestVolume = field.apply(window.getFirst());
        if (!validVolume(latestVolume)) return null;
        BigDecimal previousSum = BigDecimal.ZERO;
        for (int i = 1; i <= lookback; i++) {
            BigDecimal volume = field.apply(window.get(i));
            if (!validVolume(volume)) return null;
            previousSum = previousSum.add(volume);
        }
        return previousSum.signum() == 0 ? null
                : latestVolume.multiply(BigDecimal.valueOf(lookback)).divide(previousSum, 10, RoundingMode.HALF_UP);
    }

    private static boolean validVolume(BigDecimal volume) {
        return volume != null && volume.signum() >= 0;
    }

    private static List<Candle> usableCandles(List<Candle> candles, boolean requireConfirmed) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }
        List<Candle> result = new ArrayList<>();
        for (Candle candle : candles) {
            if (candle != null && (!requireConfirmed || candle.confirmed())) {
                result.add(candle);
            }
        }
        return result;
    }

    private static boolean hasPosition(Facts context) {
        // Exchange balances can include manual holdings outside this strategy.
        TradingState state = context.getTradingState();
        return state != null && state.getTrackedBaseAmount() != null && state.getTrackedBaseAmount().signum() > 0;
    }

    private static BigDecimal exitBaseAmount(Facts context) {
        TradingState state = context.getTradingState();
        if (state != null && state.getTrackedBaseAmount() != null && state.getTrackedBaseAmount().signum() > 0) {
            return state.getTrackedBaseAmount();
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal firstPositive(BigDecimal first, BigDecimal fallback) {
        return first != null && first.signum() > 0 ? first : fallback == null ? BigDecimal.ZERO : fallback;
    }

}
