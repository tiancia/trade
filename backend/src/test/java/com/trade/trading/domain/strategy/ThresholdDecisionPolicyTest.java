package com.trade.trading.domain.strategy;

import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.domain.model.TradingState;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ThresholdDecisionPolicyTest {
    private final ThresholdDecisionPolicy policy = new ThresholdDecisionPolicy();
    private final ThresholdDecisionPolicy.Settings settings = new ThresholdDecisionPolicy.Settings(
            new BigDecimal("0.02"), new BigDecimal("3"), new BigDecimal("0.10"),
            null, null, null, 2, 1, true);

    @Test
    void derivativeEntryUsesResolvedFallbackSizeAndConfirmedCandles() {
        var candles = List.of(candle("999", "999", false), candle("110", "300", true), candle("100", "100", true));
        var result = policy.evaluate(facts(candles, new TradingState(), true), settings);
        assertEquals(TradingAction.OPEN_LONG, result.getAction());
        assertEquals(new BigDecimal("2"), result.getOrderSize());
        assertEquals(new BigDecimal("20"), result.getBuyQuoteAmount());
    }

    @Test
    void lossExitHasPriorityAndUsesTrackedQuantity() {
        var state = new TradingState().setTrackedBaseAmount(new BigDecimal("0.4"))
                .setAverageCost(new BigDecimal("100"));
        var result = policy.evaluate(facts(List.of(candle("89", "100", true), candle("100", "100", true)),
                state, false), settings);
        assertEquals(TradingAction.SELL, result.getAction());
        assertEquals(new BigDecimal("0.4"), result.getSellBaseAmount());
        assertEquals("Tracked position floating loss threshold reached", result.getReason());
    }

    private static ThresholdDecisionPolicy.Candle candle(String close, String volume, boolean confirmed) {
        return new ThresholdDecisionPolicy.Candle(new BigDecimal(close), new BigDecimal(volume), BigDecimal.ZERO, confirmed);
    }

    private static ThresholdDecisionPolicy.Facts facts(List<ThresholdDecisionPolicy.Candle> candles,
                                                       TradingState state, boolean derivative) {
        return new ThresholdDecisionPolicy.Facts("threshold", "1m", candles, state,
                BigDecimal.ZERO, derivative, new BigDecimal("20"), new BigDecimal("2"));
    }
}
