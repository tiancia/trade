package com.trade.trading.domain.strategy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TechnicalIndicatorsTest {
    private static final double EPSILON = 1e-9;

    @Test
    void matchesArithmeticOraclesForEmaBandsAndPriorBarBaselines() {
        List<TechnicalIndicators.Candle> candles = closes(1, 2, 3, 4, 5, 6, 7, 8);

        TechnicalIndicators.Snapshot result = TechnicalIndicators.calculate(
                candles, 3, 5, 3, 3, 3, 3, 2, 3, 3);

        assertEquals(8, result.close(), EPSILON);
        assertEquals(7, result.previousClose(), EPSILON);
        assertEquals(7, result.fastEma(), EPSILON);
        assertEquals(6, result.previousFastEma(), EPSILON);
        assertEquals(6, result.slowEma(), EPSILON);
        assertEquals(100, result.rsi(), EPSILON);
        assertEquals(100, result.previousRsi(), EPSILON);
        assertEquals(1.5, result.atr(), EPSILON);
        assertEquals(100, result.adx(), EPSILON);
        assertEquals(7, result.bollingerMiddle(), EPSILON);
        double populationDeviation = Math.sqrt(2.0 / 3);
        assertEquals(7 + 2 * populationDeviation, result.bollingerUpper(), EPSILON);
        assertEquals(7 - 2 * populationDeviation, result.bollingerLower(), EPSILON);
        assertEquals(6 - 2 * populationDeviation, result.previousBollingerLower(), EPSILON);
        assertEquals(7.5, result.donchianHigh(), EPSILON);
        assertEquals(80.0 / 60, result.volumeRatio(), EPSILON);
    }

    @Test
    void matchesHandCalculatedWilderRsiAtrAndAdxAcrossTrendReversal() {
        List<TechnicalIndicators.Candle> candles = new ArrayList<>();
        double[] prices = {30, 31, 30, 29, 28, 29, 31, 32};
        for (int i = 0; i < prices.length; i++) {
            double price = prices[i];
            candles.add(new TechnicalIndicators.Candle(i, price, price + 1, price - 1, price, 100));
        }

        TechnicalIndicators.Snapshot result = calculate(candles, 3);

        // After the first three changes, gains/losses are 1/3 and 2/3.
        // At the penultimate bar they are 80/81 and 28/81, then 241/243 and 56/243.
        assertEquals(24_100.0 / 297, result.rsi(), EPSILON);
        assertEquals(2_000.0 / 27, result.previousRsi(), EPSILON);
        assertEquals(20.0 / 9, result.atr(), EPSILON);
        // First ADX = mean(100/3, 500/9, 100/27) = 2500/81.
        // Two further Wilder updates with DX = 1300/27 and 18500/297 follow.
        assertEquals(362_300.0 / 8_019, result.adx(), EPSILON);
    }

    @Test
    void trueRangeIncludesGapsFromThePreviousClose() {
        TechnicalIndicators.Snapshot result = calculate(closes(10, 10, 20, 20, 20), 2);

        // TR = 1, 10.5, 1, 1; ATR(2) = 5.75, 3.375, 2.1875.
        assertEquals(2.1875, result.atr(), EPSILON);
    }

    @Test
    void flatPricesAndZeroVolumesProduceFiniteNeutralIndicators() {
        List<TechnicalIndicators.Candle> candles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            candles.add(new TechnicalIndicators.Candle(i, 10, 10, 10, 10, 0));
        }

        TechnicalIndicators.Snapshot result = calculate(candles, 3);

        assertEquals(50, result.rsi(), EPSILON);
        assertEquals(50, result.previousRsi(), EPSILON);
        assertEquals(0, result.atr(), EPSILON);
        assertEquals(0, result.adx(), EPSILON);
        assertEquals(10, result.bollingerLower(), EPSILON);
        assertEquals(10, result.bollingerUpper(), EPSILON);
        assertEquals(0, result.volumeRatio(), EPSILON);
    }

    @Test
    void fallingPricesProduceZeroRsiAndStrongAdx() {
        TechnicalIndicators.Snapshot result = calculate(closes(8, 7, 6, 5, 4, 3, 2), 3);

        assertEquals(0, result.rsi(), EPSILON);
        assertEquals(0, result.previousRsi(), EPSILON);
        assertEquals(100, result.adx(), EPSILON);
    }

    @Test
    void excludesCurrentHighAndVolumeFromBreakoutAndVolumeBaselines() {
        List<TechnicalIndicators.Candle> candles = closes(10, 10, 10, 10, 10, 12);
        candles.set(5, new TechnicalIndicators.Candle(5, 12, 1_000, 11, 12, 900));

        TechnicalIndicators.Snapshot result = calculate(candles, 3);

        assertEquals(10.5, result.donchianHigh(), EPSILON);
        assertEquals(900.0 / 40, result.volumeRatio(), EPSILON);
    }

    @Test
    void previousValuesDoNotChangeWhenOnlyTheFinalBarChanges() {
        List<TechnicalIndicators.Candle> original = closes(10, 11, 10, 12, 11, 13, 12);
        List<TechnicalIndicators.Candle> changed = new ArrayList<>(original);
        changed.set(6, new TechnicalIndicators.Candle(6, 100, 101, 99, 100, 1_000));

        TechnicalIndicators.Snapshot before = calculate(original, 3);
        TechnicalIndicators.Snapshot after = calculate(changed, 3);

        assertEquals(before.previousClose(), after.previousClose(), EPSILON);
        assertEquals(before.previousFastEma(), after.previousFastEma(), EPSILON);
        assertEquals(before.previousRsi(), after.previousRsi(), EPSILON);
        assertEquals(before.previousBollingerLower(), after.previousBollingerLower(), EPSILON);
        assertEquals(before.donchianHigh(), after.donchianHigh(), EPSILON);
    }

    @Test
    void rejectsInsufficientHistoryAndReportsExactMinimumIncludingAdxWarmup() {
        assertEquals(28, TechnicalIndicators.requiredCandles(9, 21, 14, 14, 14, 20, 20, 20));
        assertEquals(6, TechnicalIndicators.requiredCandles(3, 3, 3, 3, 3, 3, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> calculate(closes(10, 11, 12, 13, 14), 3));
        assertThrows(IllegalArgumentException.class, () -> calculate(null, 3));
        assertThrows(IllegalArgumentException.class, () -> calculate(List.of(), 3));
        assertTrue(Double.isFinite(calculate(closes(10, 11, 12, 13, 14, 15), 3).adx()));
    }

    @Test
    void rejectsNonPositivePeriodsInvalidBandWidthAndOverflowingRequirements() {
        assertThrows(IllegalArgumentException.class,
                () -> TechnicalIndicators.requiredCandles(0, 3, 3, 3, 3, 3, 3, 3));
        assertThrows(IllegalArgumentException.class,
                () -> TechnicalIndicators.requiredCandles(3, 3, 3, 3, Integer.MAX_VALUE, 3, 3, 3));
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TechnicalIndicators.calculate(closes(10, 11, 12, 13, 14, 15),
                            3, 3, 3, 3, 3, 3, invalid, 3, 3));
        }
    }

    @Test
    void rejectsUnorderedDuplicateMissingOrInvalidOhlcv() {
        List<TechnicalIndicators.Candle> invalidCandles = new ArrayList<>();
        invalidCandles.add(null);
        invalidCandles.add(new TechnicalIndicators.Candle(-1, 10, 11, 9, 10, 1));
        invalidCandles.add(new TechnicalIndicators.Candle(3, 10, 11, 9, 10, 1));
        invalidCandles.add(new TechnicalIndicators.Candle(5, Double.NaN, 11, 9, 10, 1));
        invalidCandles.add(new TechnicalIndicators.Candle(5, 10, Double.POSITIVE_INFINITY, 9, 10, 1));
        invalidCandles.add(new TechnicalIndicators.Candle(5, 10, 11, 0, 10, 1));
        invalidCandles.add(new TechnicalIndicators.Candle(5, 10, 11, 9, Double.NaN, 1));
        invalidCandles.add(new TechnicalIndicators.Candle(5, 10, 11, 9, 10, -1));
        invalidCandles.add(new TechnicalIndicators.Candle(5, 10, 11, 9, 10, Double.NaN));
        invalidCandles.add(new TechnicalIndicators.Candle(5, 10, 9, 8, 10, 1));
        invalidCandles.add(new TechnicalIndicators.Candle(5, 10, 12, 11, 10, 1));
        for (TechnicalIndicators.Candle invalid : invalidCandles) {
            List<TechnicalIndicators.Candle> candles = closes(10, 11, 12, 13, 14, 15);
            candles.set(5, invalid);
            assertThrows(IllegalArgumentException.class, () -> calculate(candles, 3));
        }
        List<TechnicalIndicators.Candle> duplicate = closes(10, 11, 12, 13, 14, 15);
        duplicate.set(5, new TechnicalIndicators.Candle(4, 15, 16, 14, 15, 1));
        assertThrows(IllegalArgumentException.class, () -> calculate(duplicate, 3));
    }

    private static TechnicalIndicators.Snapshot calculate(List<TechnicalIndicators.Candle> candles, int period) {
        return TechnicalIndicators.calculate(candles, period, period, period, period,
                period, period, 2, period, period);
    }

    private static List<TechnicalIndicators.Candle> closes(double... prices) {
        List<TechnicalIndicators.Candle> candles = new ArrayList<>();
        for (int i = 0; i < prices.length; i++) {
            double price = prices[i];
            candles.add(new TechnicalIndicators.Candle(i, price, price + 0.5, price - 0.5,
                    price, (i + 1) * 10));
        }
        return candles;
    }
}
