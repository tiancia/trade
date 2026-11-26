package com.trade.trading.application.strategy;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.application.market.CandleFreshness;
import com.trade.trading.application.market.TradingMarketInputs;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.strategy.ThresholdDecisionPolicy;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;

/** Registered strategy adapter: resolves configuration and translates market inputs. */
@Component
public class ThresholdEventStrategy implements TradingStrategy<ThresholdEventStrategyConfig> {
    public static final String TYPE = "threshold-event";
    private final ThresholdDecisionPolicy policy = new ThresholdDecisionPolicy();

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
        ThresholdEventStrategyConfig settings = normalize(config, context.getProperties());
        List<CandleResp> window = currentWindow(context, settings);
        if (window.isEmpty()) {
            return StrategyDecision.hold(context.getStrategyId(),
                    "Invalid or missing current " + context.getBar() + " threshold candle");
        }
        TradingProperties properties = context.getProperties();
        ThresholdDecisionPolicy.Facts facts = new ThresholdDecisionPolicy.Facts(
                context.getStrategyId(), context.getBar(),
                TradingMarketInputs.thresholdCandles(window),
                context.tradingState(),
                TradingMarketInputs.availableBalance(context.getMarketContext() == null ? null : context.getMarketContext().getBaseBalance()),
                properties != null && properties.isDerivativeInstrument(),
                properties == null ? BigDecimal.ZERO : properties.getMaxBuyQuoteAmount(),
                properties == null ? BigDecimal.ZERO : properties.getMaxDerivativeOrderSize());
        StrategyDecision decision = policy.evaluate(facts, new ThresholdDecisionPolicy.Settings(
                settings.getPriceMoveTriggerPercent(), settings.getVolumeSpikeMultiplier(),
                settings.getFloatingLossTriggerPercent(), settings.getBuyQuoteAmount(),
                settings.getSellBaseAmount(), settings.getOrderSize(), settings.getPriceMoveWindowCandles(),
                settings.getVolumeLookbackCandles(), settings.isRequireConfirmedCandle()));
        CandleResp signal = window.getFirst();
        if (!decision.isHold() && "15m".equalsIgnoreCase(context.getBar().trim()) && "1".equals(signal.getConfirm())) {
            decision.getMetadata().put("signalBar", "15m");
            decision.getMetadata().put("signalCandleTs", CandleFreshness.timestampMillis(signal));
        }
        return decision;
    }

    /** Only the consecutive prefix ending at the signal bar can support a price or volume comparison. */
    private static List<CandleResp> currentWindow(StrategyEvaluationContext context, ThresholdEventStrategyConfig settings) {
        List<CandleResp> rows = context.candlesNewestFirst();
        long interval = CandleFreshness.intervalMillis(context.getBar());
        if (rows.isEmpty() || interval == 0 || context.getEvaluatedAt() == null) return List.of();

        long now = context.getEvaluatedAt().toEpochMilli();
        long currentOpen = Math.floorDiv(now, interval) * interval;
        CandleResp newest = rows.getFirst();
        boolean forming = newest != null && CandleFreshness.timestampMillis(newest) == currentOpen
                && "0".equals(newest.getConfirm());
        boolean confirmedOnly = settings.isRequireConfirmedCandle();
        int start = forming && confirmedOnly ? 1 : 0;
        long expected = forming && !confirmedOnly ? currentOpen : currentOpen - interval;
        int needed = Math.max(settings.getPriceMoveWindowCandles(), settings.getVolumeLookbackCandles() + 1);
        int end = start;
        while (end < rows.size() && end - start < needed) {
            CandleResp row = rows.get(end);
            if (row == null || expected <= 0 || CandleFreshness.timestampMillis(row) != expected) break;
            if (!"1".equals(row.getConfirm()) && (confirmedOnly || !"0".equals(row.getConfirm()))) break;
            // A duplicate or newer neighbour makes this close ambiguous, even at the edge of the window.
            if (end + 1 < rows.size() && CandleFreshness.timestampMillis(rows.get(end + 1)) >= expected) break;
            end++;
            expected -= interval;
        }
        return rows.subList(start, end);
    }

    static ThresholdEventStrategyConfig normalize(
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

    private static BigDecimal firstPositive(BigDecimal first, BigDecimal fallback) {
        return first != null && first.signum() > 0 ? first : fallback == null ? BigDecimal.ZERO : fallback;
    }

}
