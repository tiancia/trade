package com.trade.trading.application.strategy;

import com.trade.trading.application.market.TradingMarketInputs;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.strategy.ThresholdDecisionPolicy;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

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
        TradingProperties properties = context.getProperties();
        ThresholdDecisionPolicy.Facts facts = new ThresholdDecisionPolicy.Facts(
                context.getStrategyId(), context.getBar(),
                TradingMarketInputs.thresholdCandles(context.candlesNewestFirst()),
                context.tradingState(),
                TradingMarketInputs.availableBalance(context.getMarketContext() == null ? null : context.getMarketContext().getBaseBalance()),
                properties != null && properties.isDerivativeInstrument(),
                properties == null ? BigDecimal.ZERO : properties.getMaxBuyQuoteAmount(),
                properties == null ? BigDecimal.ZERO : properties.getMaxDerivativeOrderSize());
        return policy.evaluate(facts, new ThresholdDecisionPolicy.Settings(
                settings.getPriceMoveTriggerPercent(), settings.getVolumeSpikeMultiplier(),
                settings.getFloatingLossTriggerPercent(), settings.getBuyQuoteAmount(),
                settings.getSellBaseAmount(), settings.getOrderSize(), settings.getPriceMoveWindowCandles(),
                settings.getVolumeLookbackCandles(), settings.isRequireConfirmedCandle()));
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

    private static BigDecimal firstPositive(BigDecimal first, BigDecimal fallback) {
        return first != null && first.signum() > 0 ? first : fallback == null ? BigDecimal.ZERO : fallback;
    }

}
