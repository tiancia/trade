package com.trade.trading.domain.strategy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntradayTechnicalPolicyTest {
    private final IntradayTechnicalPolicy policy = new IntradayTechnicalPolicy();
    private static IntradayTechnicalPolicy.Settings settings(IntradayTechnicalPolicy.Mode mode) {
        return new IntradayTechnicalPolicy.Settings(mode, 18, 20, 20, 72, 0.8, .001, .03, 2.5, .006, .018, .003);
    }
    private static TechnicalIndicators.Snapshot snapshot(double previousClose, double adx, double rsi, double close,
                                                          double middle, double previousLower, double high) {
        return new TechnicalIndicators.Snapshot(close, previousClose, 100, 99, 100, rsi, rsi - 2, 1,
                adx, middle, middle + 2, middle - 2, previousLower, high, 1.5);
    }
    private IntradayTechnicalPolicy.Result evaluate(TechnicalIndicators.Snapshot s, IntradayTechnicalPolicy.Mode mode) {
        return policy.evaluate(s, settings(mode), false, 0, .0024, 1.5, .001);
    }

    @Test void independentSetupsEnterInAppropriateRegimes() {
        var trend = snapshot(99.8, 24, 55, 100.5, 102, 98, 102);
        assertEquals(IntradayTechnicalPolicy.Action.ENTER, evaluate(trend, IntradayTechnicalPolicy.Mode.TREND_PULLBACK).action());
        assertEquals(IntradayTechnicalPolicy.Action.HOLD, evaluate(trend, IntradayTechnicalPolicy.Mode.VOLUME_BREAKOUT).action());
        var breakout = snapshot(100.5, 24, 62, 101, 100, 98, 100.8);
        assertEquals(IntradayTechnicalPolicy.Action.ENTER, evaluate(breakout, IntradayTechnicalPolicy.Mode.VOLUME_BREAKOUT).action());
        var range = snapshot(99, 15, 35, 100, 101.8, 99.1, 103);
        assertEquals(IntradayTechnicalPolicy.Action.ENTER, evaluate(range, IntradayTechnicalPolicy.Mode.RANGE_REVERSION).action());
        assertEquals(IntradayTechnicalPolicy.Action.HOLD, evaluate(range, IntradayTechnicalPolicy.Mode.TREND_PULLBACK).action());
    }

    @Test void rejectsChasingInsufficientNetRewardAndInappropriateRegime() {
        var s = snapshot(100.5, 24, 62, 101, 100, 98, 100.8);
        assertEquals(IntradayTechnicalPolicy.Action.HOLD, policy.evaluate(s, settings(IntradayTechnicalPolicy.Mode.VOLUME_BREAKOUT), false, 0, .015, 1.5, .001).action());
        assertEquals(IntradayTechnicalPolicy.Action.HOLD, evaluate(snapshot(99, 25, 35, 100, 101.8, 99.1, 103), IntradayTechnicalPolicy.Mode.RANGE_REVERSION).action());
        assertEquals(IntradayTechnicalPolicy.Action.HOLD, evaluate(snapshot(99, 15, 35, 100, 100.4, 99.1, 103), IntradayTechnicalPolicy.Mode.RANGE_REVERSION).action());
        assertEquals(IntradayTechnicalPolicy.Action.HOLD, evaluate(snapshot(100.5, 24, 62, 103, 100, 98, 100.8), IntradayTechnicalPolicy.Mode.VOLUME_BREAKOUT).action());
    }

    @Test void exitsBeforeEntryFiltersAndDoesNotPyramid() {
        var config = settings(IntradayTechnicalPolicy.Mode.VOLUME_BREAKOUT);
        assertEquals(IntradayTechnicalPolicy.Action.EXIT, policy.evaluate(snapshot(100, 24, 62, 99, 100, 98, 100), config, true, 100, .0024, 1.5, .001).action());
        assertEquals(IntradayTechnicalPolicy.Action.EXIT, policy.evaluate(snapshot(100, 24, 62, 102, 100, 98, 100), config, true, 100, .0024, 1.5, .001).action());
        var hold = policy.evaluate(snapshot(100, 24, 62, 101, 100, 98, 100.8), config, true, 100, .0024, 1.5, .001);
        assertEquals(IntradayTechnicalPolicy.Action.HOLD, hold.action());
        assertTrue(hold.reason().contains("no pyramiding"));
    }
}
