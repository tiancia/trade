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
        Settings normalized = config;
        List<Candle> candles = usableCandles(context.getCandles(), normalized.isRequireConfirmedCandle());
        if (candles.isEmpty()) {
            return StrategyDecision.hold(context.getStrategyId(), "No usable candles available");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        PriceMove priceMove = priceMove(candles, normalized.getPriceMoveWindowCandles());
        VolumeSpike volumeSpike = volumeSpike(candles, normalized.getVolumeLookbackCandles());
        FloatingLoss floatingLoss = floatingLoss(candles.getFirst(), context.getTradingState(), normalized);
        metadata.put("bar", context.getBar());
        metadata.put("priceMovePercent", priceMove.changePercent());
        metadata.put("priceMoveThresholdPercent", normalized.getPriceMoveTriggerPercent());
        metadata.put("volumeSpikeRatio", volumeSpike.ratio());
        metadata.put("volumeSpikeThresholdMultiplier", normalized.getVolumeSpikeMultiplier());
        metadata.put("floatingLossPercent", floatingLoss.pnlPercent());
        metadata.put("floatingLossThresholdPercent", normalized.getFloatingLossTriggerPercent());

        if (hasPosition(context) && shouldExit(priceMove, floatingLoss, normalized)) {
            return exitDecision(context, normalized, metadata, exitReason(priceMove, floatingLoss, normalized));
        }

        if (shouldEnter(priceMove, volumeSpike, normalized)) {
            return enterDecision(context, normalized, metadata);
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

        BigDecimal sellBaseAmount = firstPositive(config.getSellBaseAmount(), exitBaseAmount(context));
        BigDecimal orderSize = firstPositive(config.getOrderSize(), context.getMaxDerivativeOrderSize());
        StrategyDecision decision = context.isDerivativeInstrument()
                ? StrategyDecision.closeLong(context.getStrategyId(), orderSize, reason)
                        .setSellBaseAmount(sellBaseAmount)
                : StrategyDecision.sell(context.getStrategyId(), sellBaseAmount, reason)
                        .setOrderSize(orderSize);
        return decision.setMetadata(metadata);
    }

    private static boolean shouldEnter(
            PriceMove priceMove,
            VolumeSpike volumeSpike,
            Settings config
    ) {
        return priceMove.changePercent().compareTo(config.getPriceMoveTriggerPercent()) >= 0
                && volumeSpike.ratio().compareTo(config.getVolumeSpikeMultiplier()) >= 0;
    }

    private static boolean shouldExit(
            PriceMove priceMove,
            FloatingLoss floatingLoss,
            Settings config
    ) {
        BigDecimal reverseThreshold = config.getPriceMoveTriggerPercent().negate();
        BigDecimal lossThreshold = config.getFloatingLossTriggerPercent().negate();
        return priceMove.changePercent().compareTo(reverseThreshold) <= 0
                || floatingLoss.pnlPercent().compareTo(lossThreshold) <= 0;
    }

    private static String exitReason(
            PriceMove priceMove,
            FloatingLoss floatingLoss,
            Settings config
    ) {
        if (floatingLoss.pnlPercent().compareTo(config.getFloatingLossTriggerPercent().negate()) <= 0) {
            return "Tracked position floating loss threshold reached";
        }
        return "Reverse price move threshold reached";
    }

    private static PriceMove priceMove(List<Candle> confirmed, int windowCandles) {
        int window = Math.max(windowCandles, 2);
        if (confirmed.size() < window) {
            return new PriceMove(BigDecimal.ZERO);
        }
        BigDecimal current = confirmed.getFirst().close();
        BigDecimal base = confirmed.get(window - 1).close();
        return new PriceMove(TradingMath.percentChange(current, base));
    }

    private static VolumeSpike volumeSpike(List<Candle> confirmed, int lookbackCandles) {
        int lookback = Math.max(lookbackCandles, 1);
        if (confirmed.size() < lookback + 1) {
            return new VolumeSpike(BigDecimal.ZERO);
        }

        BigDecimal latestVolume = quoteVolume(confirmed.getFirst());
        BigDecimal previousSum = BigDecimal.ZERO;
        for (int i = 1; i <= lookback; i++) {
            previousSum = previousSum.add(quoteVolume(confirmed.get(i)));
        }
        BigDecimal average = previousSum.divide(new BigDecimal(lookback), 10, RoundingMode.HALF_UP);
        if (average.signum() <= 0) {
            return new VolumeSpike(BigDecimal.ZERO);
        }
        return new VolumeSpike(latestVolume.divide(average, 10, RoundingMode.HALF_UP));
    }

    private static FloatingLoss floatingLoss(
            Candle latestConfirmed,
            TradingState tradingState,
            Settings config
    ) {
        if (tradingState == null || !tradingState.hasTrackedPosition()) {
            return new FloatingLoss(BigDecimal.ZERO);
        }
        BigDecimal latestClose = latestConfirmed.close();
        return new FloatingLoss(TradingMath.percentChange(latestClose, tradingState.getAverageCost()));
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

    private static BigDecimal quoteVolume(Candle candle) {
        BigDecimal quoteVolume = candle == null ? BigDecimal.ZERO : candle.quoteVolume();
        if (quoteVolume.signum() > 0) {
            return quoteVolume;
        }
        return candle == null ? BigDecimal.ZERO : candle.volume();
    }

    private static boolean hasPosition(Facts context) {
        if (context.getTradingState() != null && context.getTradingState().hasTrackedPosition()) {
            return true;
        }
        return context.getAvailableBase().signum() > 0;
    }

    private static BigDecimal exitBaseAmount(Facts context) {
        TradingState state = context.getTradingState();
        if (state != null && state.getTrackedBaseAmount() != null && state.getTrackedBaseAmount().signum() > 0) {
            return state.getTrackedBaseAmount();
        }
        return context.getAvailableBase();
    }

    private static BigDecimal firstPositive(BigDecimal first, BigDecimal fallback) {
        return first != null && first.signum() > 0 ? first : fallback == null ? BigDecimal.ZERO : fallback;
    }

    private record PriceMove(BigDecimal changePercent) {
    }

    private record VolumeSpike(BigDecimal ratio) {
    }

    private record FloatingLoss(BigDecimal pnlPercent) {
    }
}
