package com.trade.trading.application.strategy;

import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.strategy.IntradayTechnicalPolicy;
import com.trade.trading.domain.strategy.TechnicalIndicators;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Shares exactly the same closed 15m window between realtime and historical evaluation. */
@Component
public class IntradayTechnicalStrategy implements TradingStrategy<IntradayTechnicalStrategyConfig> {
    public static final String TYPE = "intraday-technical";
    private static final long BAR_MILLIS = 900_000L;
    private final IntradayTechnicalPolicy policy = new IntradayTechnicalPolicy();

    @Override public String type() { return TYPE; }
    @Override public Class<IntradayTechnicalStrategyConfig> configType() { return IntradayTechnicalStrategyConfig.class; }

    @Override
    public StrategyDecision evaluate(StrategyEvaluationContext context, IntradayTechnicalStrategyConfig config) {
        String id = context.getStrategyId();
        if (!"15m".equals(context.getBar()) || context.getProperties() == null || !context.getProperties().isSpotInstrument()) {
            return StrategyDecision.hold(id, "Intraday technical strategies require SPOT and 15m candles");
        }
        try {
            List<TechnicalIndicators.Candle> candles = confirmedWindow(context, config.warmupCandles());
            if (candles.size() < config.warmupCandles()) return StrategyDecision.hold(id, "Insufficient confirmed 15m indicator warmup");
            var s = TechnicalIndicators.calculate(candles, config.getEmaFastPeriod(), config.getEmaSlowPeriod(),
                    config.getRsiPeriod(), config.getAtrPeriod(), config.getAdxPeriod(), config.getBollingerPeriod(),
                    config.getBollingerDeviations().doubleValue(), config.getBreakoutPeriod(), config.getVolumePeriod());
            var state = context.tradingState();
            boolean hasPosition = state != null && state.getTrackedBaseAmount() != null && state.getTrackedBaseAmount().signum() > 0;
            if (!hasPosition && state != null && state.getRiskState() != null
                    && state.getRiskState().getLastTradeTime() != null) {
                java.time.Instant lastTrade;
                try { lastTrade = java.time.Instant.parse(state.getRiskState().getLastTradeTime()); }
                catch (RuntimeException e) { return StrategyDecision.hold(id, "Invalid last-trade time; entry deferred"); }
                if (lastTrade.toEpochMilli() >= candles.getLast().timestamp() + BAR_MILLIS) {
                    return StrategyDecision.hold(id, "Wait for next closed 15m candle after the previous trade");
                }
            }
            double averageCost = state == null || state.getAverageCost() == null ? 0 : state.getAverageCost().doubleValue();
            var properties = context.getProperties();
            double fee = context.getEvaluationFeeRate() == null ? properties.getTakerFeeRate().doubleValue() : context.getEvaluationFeeRate().doubleValue();
            double slippage = context.getEvaluationSlippageRate() == null ? config.getEstimatedSlippageRate().doubleValue() : context.getEvaluationSlippageRate().doubleValue();
            double roundTripCost = 2 * (fee + slippage);
            var result = policy.evaluate(s, config.settings(), hasPosition, averageCost, roundTripCost,
                    properties.getStrategy().getMinRiskRewardRatio().doubleValue(), properties.getMinExpectedNetEdgePercent().doubleValue());
            StrategyDecision decision = switch (result.action()) {
                case ENTER -> StrategyDecision.buy(id, config.getBuyQuoteAmount(), result.reason());
                case EXIT -> StrategyDecision.sell(id, state.getTrackedBaseAmount(), result.reason());
                case HOLD -> StrategyDecision.hold(id, result.reason());
            };
            var metadata = new LinkedHashMap<>(result.metadata());
            metadata.put("signalBar", "15m");
            metadata.put("signalCandleTs", candles.getLast().timestamp());
            metadata.put("warmupCandles", candles.size());
            metadata.put("exitEvaluation", "CANDLE_CLOSE");
            return decision.setMetadata(metadata);
        } catch (IllegalArgumentException e) {
            return StrategyDecision.hold(id, "Invalid or incomplete 15m candles: " + e.getMessage());
        }
    }

    private static List<TechnicalIndicators.Candle> confirmedWindow(StrategyEvaluationContext context, int limit) {
        List<CandleResp> confirmed = context.candlesNewestFirst().stream()
                .filter(c -> c != null && "1".equals(c.getConfirm()))
                .sorted(Comparator.comparingLong(c -> Long.parseLong(c.getTs())))
                .toList();
        confirmed = confirmed.subList(Math.max(0, confirmed.size() - limit), confirmed.size());
        List<TechnicalIndicators.Candle> result = new ArrayList<>();
        long previous = 0;
        for (CandleResp c : confirmed) {
            long timestamp = Long.parseLong(c.getTs());
            if (timestamp <= 0 || timestamp % BAR_MILLIS != 0 || (previous > 0 && timestamp - previous != BAR_MILLIS)) {
                throw new IllegalArgumentException("timestamps are not contiguous 15m bars");
            }
            // Live evaluation uses wall time; backtest evaluation uses the signal close time.
            if (context.getEvaluatedAt() == null || timestamp + BAR_MILLIS > context.getEvaluatedAt().toEpochMilli()) {
                throw new IllegalArgumentException("confirmed candle has not closed at evaluation time");
            }
            result.add(new TechnicalIndicators.Candle(timestamp, number(c.getOpen()), number(c.getHigh()),
                    number(c.getLow()), number(c.getClose()), number(c.getVol())));
            previous = timestamp;
        }
        if (!result.isEmpty() && context.getEvaluatedAt().toEpochMilli() - (result.getLast().timestamp() + BAR_MILLIS) >= BAR_MILLIS) {
            throw new IllegalArgumentException("latest confirmed candle is stale");
        }
        return result;
    }

    private static double number(String value) {
        if (value == null) throw new IllegalArgumentException("missing OHLCV value");
        return new BigDecimal(value).doubleValue();
    }
}
