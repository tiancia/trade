package com.trade.trading.market;

import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.TradingEvent;
import com.trade.trading.model.TradingState;
import com.trade.trading.support.TradingMath;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TradingEventDetector {
    private final TradingProperties properties;

    public TradingEventDetector(TradingProperties properties) {
        this.properties = properties;
    }

    public List<TradingEvent> detect(TickerResp ticker, List<CandleResp> oneMinuteCandles, TradingState state) {
        List<TradingEvent> events = new ArrayList<>();
        List<CandleResp> confirmedCandles = confirmed(oneMinuteCandles);
        BigDecimal lastPrice = TradingMath.decimal(ticker == null ? null : ticker.getLast());

        detectPriceMove(events, lastPrice, confirmedCandles);
        detectVolumeSpike(events, confirmedCandles);
        detectFloatingLoss(events, lastPrice, state);

        return events;
    }

    private void detectPriceMove(List<TradingEvent> events, BigDecimal lastPrice, List<CandleResp> confirmedCandles) {
        if (lastPrice.signum() <= 0 || confirmedCandles.size() < 5) {
            return;
        }

        BigDecimal fiveMinuteBase = TradingMath.decimal(confirmedCandles.get(4).getClose());
        BigDecimal change = TradingMath.percentChange(lastPrice, fiveMinuteBase);
        if (change.abs().compareTo(properties.getPriceMoveTriggerPercent()) >= 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lastPrice", lastPrice);
            details.put("fiveMinuteBasePrice", fiveMinuteBase);
            details.put("changePercent", change);
            details.put("thresholdPercent", properties.getPriceMoveTriggerPercent());
            events.add(new TradingEvent("PRICE_MOVE_5M", "5 minute price move threshold reached", details));
        }
    }

    private void detectVolumeSpike(List<TradingEvent> events, List<CandleResp> confirmedCandles) {
        if (confirmedCandles.size() < 21) {
            return;
        }

        BigDecimal latestVolume = TradingMath.decimal(confirmedCandles.get(0).getVolCcyQuote());
        BigDecimal previousSum = BigDecimal.ZERO;
        for (int i = 1; i <= 20; i++) {
            previousSum = previousSum.add(TradingMath.decimal(confirmedCandles.get(i).getVolCcyQuote()));
        }
        BigDecimal average = previousSum.divide(new BigDecimal("20"), 10, java.math.RoundingMode.HALF_UP);
        if (average.signum() <= 0) {
            return;
        }

        BigDecimal ratio = latestVolume.divide(average, 10, java.math.RoundingMode.HALF_UP);
        if (ratio.compareTo(properties.getVolumeSpikeMultiplier()) >= 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("latestOneMinuteQuoteVolume", latestVolume);
            details.put("previousTwentyAverageQuoteVolume", average);
            details.put("ratio", ratio);
            details.put("thresholdMultiplier", properties.getVolumeSpikeMultiplier());
            events.add(new TradingEvent("VOLUME_SPIKE", "1 minute quote volume spike threshold reached", details));
        }
    }

    private void detectFloatingLoss(List<TradingEvent> events, BigDecimal lastPrice, TradingState state) {
        if (state == null || !state.hasTrackedPosition() || lastPrice.signum() <= 0) {
            return;
        }

        BigDecimal pnlPercent = TradingMath.percentChange(lastPrice, state.getAverageCost());
        BigDecimal lossThreshold = properties.getFloatingLossTriggerPercent().negate();
        if (pnlPercent.compareTo(lossThreshold) <= 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("lastPrice", lastPrice);
            details.put("averageCost", state.getAverageCost());
            details.put("trackedBaseAmount", state.getTrackedBaseAmount());
            details.put("unrealizedPnlPercent", pnlPercent);
            details.put("lossThresholdPercent", lossThreshold);
            events.add(new TradingEvent("FLOATING_LOSS", "Tracked position floating loss threshold reached", details));
        }
    }

    private static List<CandleResp> confirmed(List<CandleResp> candles) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }

        List<CandleResp> result = new ArrayList<>();
        for (CandleResp candle : candles) {
            if ("1".equals(candle.getConfirm())) {
                result.add(candle);
            }
        }
        return result;
    }
}
