package com.trade.trading.strategy;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.StrategyDecision;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingState;
import com.trade.common.support.TradingMath;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ThresholdEventStrategy implements TradingStrategy<ThresholdEventStrategyConfig> {
    public static final String TYPE = "threshold-event";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<ThresholdEventStrategyConfig> configType() {
        return ThresholdEventStrategyConfig.class;
    }

    @Override
    public StrategyDecision evaluate(StrategyEvaluationContext context, ThresholdEventStrategyConfig config) {
        ThresholdEventStrategyConfig normalized = normalize(config, context.getProperties());
        List<CandleResp> candles = usableCandles(context.candlesNewestFirst(), normalized.isRequireConfirmedCandle());
        if (candles.isEmpty()) {
            return StrategyDecision.hold(context.getStrategyId(), "No usable candles available");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        PriceMove priceMove = priceMove(candles, normalized.getPriceMoveWindowCandles());
        VolumeSpike volumeSpike = volumeSpike(candles, normalized.getVolumeLookbackCandles());
        FloatingLoss floatingLoss = floatingLoss(candles.getFirst(), context.tradingState(), normalized);
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

        if (priceMove.changePercent().compareTo(normalized.getPriceMoveTriggerPercent()) >= 0
                && volumeSpike.ratio().compareTo(normalized.getVolumeSpikeMultiplier()) >= 0) {
            return enterDecision(context, normalized, metadata);
        }

        return StrategyDecision.hold(context.getStrategyId(), "Threshold conditions not met")
                .setMetadata(metadata);
    }

    private static StrategyDecision enterDecision(
            StrategyEvaluationContext context,
            ThresholdEventStrategyConfig config,
            Map<String, Object> metadata
    ) {
        TradingProperties properties = context.getProperties();
        TradingAction action = properties != null && properties.isDerivativeInstrument()
                ? TradingAction.OPEN_LONG
                : TradingAction.BUY;
        return new StrategyDecision()
                .setStrategyId(context.getStrategyId())
                .setAction(action)
                .setReason("Positive price move and volume spike thresholds reached")
                .setBuyQuoteAmount(firstPositive(config.getBuyQuoteAmount(),
                        properties == null ? BigDecimal.ZERO : properties.getMaxBuyQuoteAmount()))
                .setOrderSize(firstPositive(config.getOrderSize(),
                        properties == null ? BigDecimal.ZERO : properties.getMaxDerivativeOrderSize()))
                .setMetadata(metadata);
    }

    private static StrategyDecision exitDecision(
            StrategyEvaluationContext context,
            ThresholdEventStrategyConfig config,
            Map<String, Object> metadata,
            String reason
    ) {
        TradingProperties properties = context.getProperties();
        TradingAction action = properties != null && properties.isDerivativeInstrument()
                ? TradingAction.CLOSE_LONG
                : TradingAction.SELL;
        return new StrategyDecision()
                .setStrategyId(context.getStrategyId())
                .setAction(action)
                .setReason(reason)
                .setSellBaseAmount(firstPositive(config.getSellBaseAmount(), exitBaseAmount(context)))
                .setOrderSize(firstPositive(config.getOrderSize(),
                        properties == null ? BigDecimal.ZERO : properties.getMaxDerivativeOrderSize()))
                .setMetadata(metadata);
    }

    private static boolean shouldExit(
            PriceMove priceMove,
            FloatingLoss floatingLoss,
            ThresholdEventStrategyConfig config
    ) {
        BigDecimal reverseThreshold = config.getPriceMoveTriggerPercent().negate();
        BigDecimal lossThreshold = config.getFloatingLossTriggerPercent().negate();
        return priceMove.changePercent().compareTo(reverseThreshold) <= 0
                || floatingLoss.pnlPercent().compareTo(lossThreshold) <= 0;
    }

    private static String exitReason(
            PriceMove priceMove,
            FloatingLoss floatingLoss,
            ThresholdEventStrategyConfig config
    ) {
        if (floatingLoss.pnlPercent().compareTo(config.getFloatingLossTriggerPercent().negate()) <= 0) {
            return "Tracked position floating loss threshold reached";
        }
        return "Reverse price move threshold reached";
    }

    private static PriceMove priceMove(List<CandleResp> confirmed, int windowCandles) {
        int window = Math.max(windowCandles, 2);
        if (confirmed.size() < window) {
            return new PriceMove(BigDecimal.ZERO);
        }
        BigDecimal current = TradingMath.decimal(confirmed.getFirst().getClose());
        BigDecimal base = TradingMath.decimal(confirmed.get(window - 1).getClose());
        return new PriceMove(TradingMath.percentChange(current, base));
    }

    private static VolumeSpike volumeSpike(List<CandleResp> confirmed, int lookbackCandles) {
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
            CandleResp latestConfirmed,
            TradingState tradingState,
            ThresholdEventStrategyConfig config
    ) {
        if (tradingState == null || !tradingState.hasTrackedPosition()) {
            return new FloatingLoss(BigDecimal.ZERO);
        }
        BigDecimal latestClose = TradingMath.decimal(latestConfirmed.getClose());
        return new FloatingLoss(TradingMath.percentChange(latestClose, tradingState.getAverageCost()));
    }

    private static List<CandleResp> usableCandles(List<CandleResp> candles, boolean requireConfirmed) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }
        List<CandleResp> result = new ArrayList<>();
        for (CandleResp candle : candles) {
            if (candle != null && (!requireConfirmed || "1".equals(candle.getConfirm()))) {
                result.add(candle);
            }
        }
        return result;
    }

    private static BigDecimal quoteVolume(CandleResp candle) {
        BigDecimal quoteVolume = TradingMath.decimal(candle == null ? null : candle.getVolCcyQuote());
        if (quoteVolume.signum() > 0) {
            return quoteVolume;
        }
        return TradingMath.decimal(candle == null ? null : candle.getVolCcy());
    }

    private static ThresholdEventStrategyConfig normalize(
            ThresholdEventStrategyConfig config,
            TradingProperties properties
    ) {
        ThresholdEventStrategyConfig source = config == null ? new ThresholdEventStrategyConfig() : config;
        return new ThresholdEventStrategyConfig()
                .setPriceMoveTriggerPercent(firstPositive(source.getPriceMoveTriggerPercent(),
                        properties == null ? new BigDecimal("0.02") : properties.getPriceMoveTriggerPercent()))
                .setVolumeSpikeMultiplier(firstPositive(source.getVolumeSpikeMultiplier(),
                        properties == null ? new BigDecimal("3") : properties.getVolumeSpikeMultiplier()))
                .setFloatingLossTriggerPercent(firstPositive(source.getFloatingLossTriggerPercent(),
                        properties == null ? new BigDecimal("0.10") : properties.getFloatingLossTriggerPercent()))
                .setBuyQuoteAmount(source.getBuyQuoteAmount())
                .setSellBaseAmount(source.getSellBaseAmount())
                .setOrderSize(source.getOrderSize())
                .setPriceMoveWindowCandles(source.getPriceMoveWindowCandles())
                .setVolumeLookbackCandles(source.getVolumeLookbackCandles())
                .setRequireConfirmedCandle(source.isRequireConfirmedCandle());
    }

    private static boolean hasPosition(StrategyEvaluationContext context) {
        if (context.tradingState() != null && context.tradingState().hasTrackedPosition()) {
            return true;
        }
        return available(context.getMarketContext() == null ? null : context.getMarketContext().getBaseBalance()).signum() > 0;
    }

    private static BigDecimal exitBaseAmount(StrategyEvaluationContext context) {
        TradingState state = context.tradingState();
        if (state != null && state.getTrackedBaseAmount() != null && state.getTrackedBaseAmount().signum() > 0) {
            return state.getTrackedBaseAmount();
        }
        return available(context.getMarketContext() == null ? null : context.getMarketContext().getBaseBalance());
    }

    private static BigDecimal available(BalanceDetail detail) {
        if (detail == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal availBal = TradingMath.decimal(detail.getAvailBal());
        if (availBal.signum() > 0) {
            return availBal;
        }
        return TradingMath.decimal(detail.getCashBal());
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
