package com.trade.trading.domain.strategy;

import java.util.Arrays;
import java.util.List;

/** Pure indicators over strictly chronological candles; the caller decides which bars are closed. */
public final class TechnicalIndicators {
    private TechnicalIndicators() {}

    public record Candle(long timestamp, double open, double high, double low, double close, double volume) {}

    public record Snapshot(
            double close,
            double previousClose,
            double fastEma,
            double slowEma,
            double previousFastEma,
            double rsi,
            double previousRsi,
            double atr,
            double adx,
            double bollingerMiddle,
            double bollingerUpper,
            double bollingerLower,
            double previousBollingerLower,
            double donchianHigh,
            double volumeRatio
    ) {}

    /**
     * Minimum history for a current and previous signal. EMA starts with an SMA seed;
     * RSI, ATR and ADX use Wilder seeds and smoothing. More history improves seed stability.
     */
    public static int requiredCandles(
            int fastPeriod, int slowPeriod, int rsiPeriod, int atrPeriod, int adxPeriod,
            int bandPeriod, int breakoutPeriod, int volumePeriod
    ) {
        int[] periods = {fastPeriod, slowPeriod, rsiPeriod, atrPeriod, adxPeriod,
                bandPeriod, breakoutPeriod, volumePeriod};
        for (int period : periods) {
            if (period < 1) {
                throw new IllegalArgumentException("Indicator periods must be positive");
            }
        }
        long required = Math.max(Math.max((long) fastPeriod + 1, slowPeriod), (long) rsiPeriod + 2);
        required = Math.max(required, (long) atrPeriod + 1);
        required = Math.max(required, 2L * adxPeriod);
        required = Math.max(required, (long) bandPeriod + 1);
        required = Math.max(required, (long) breakoutPeriod + 1);
        required = Math.max(required, (long) volumePeriod + 1);
        if (required > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Indicator history requirement exceeds supported list size");
        }
        return (int) required;
    }

    /**
     * Calculates the final bar and previous-bar values without future data. Bollinger
     * bands use population standard deviation. Donchian high and the average volume
     * exclude the final bar. A zero historical volume baseline gives a ratio of zero.
     */
    public static Snapshot calculate(
            List<Candle> candlesOldestFirst,
            int fastPeriod, int slowPeriod, int rsiPeriod, int atrPeriod, int adxPeriod,
            int bandPeriod, double bandDeviations, int breakoutPeriod, int volumePeriod
    ) {
        int required = requiredCandles(fastPeriod, slowPeriod, rsiPeriod, atrPeriod,
                adxPeriod, bandPeriod, breakoutPeriod, volumePeriod);
        if (!Double.isFinite(bandDeviations) || bandDeviations <= 0) {
            throw new IllegalArgumentException("Bollinger deviations must be finite and positive");
        }
        validateCandles(candlesOldestFirst, required);
        int last = candlesOldestFirst.size() - 1;
        double[] fast = ema(candlesOldestFirst, fastPeriod);
        double[] slow = ema(candlesOldestFirst, slowPeriod);
        double[] rsi = rsi(candlesOldestFirst, rsiPeriod);
        double[] atr = atr(candlesOldestFirst, atrPeriod);
        Bands currentBands = bands(candlesOldestFirst, last, bandPeriod, bandDeviations);
        Bands previousBands = bands(candlesOldestFirst, last - 1, bandPeriod, bandDeviations);
        double donchianHigh = 0;
        for (int i = last - breakoutPeriod; i < last; i++) {
            donchianHigh = Math.max(donchianHigh, candlesOldestFirst.get(i).high());
        }
        double averageVolume = 0;
        for (int i = last - volumePeriod; i < last; i++) {
            averageVolume += (candlesOldestFirst.get(i).volume() - averageVolume)
                    / (i - (last - volumePeriod) + 1);
        }
        double volumeRatio = averageVolume == 0 ? 0 : candlesOldestFirst.get(last).volume() / averageVolume;
        double adx = adx(candlesOldestFirst, adxPeriod);
        double[] values = {fast[last], slow[last], fast[last - 1], rsi[last], rsi[last - 1],
                atr[last], adx, currentBands.middle(), currentBands.upper(), currentBands.lower(),
                previousBands.lower(), donchianHigh, volumeRatio};
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Indicator calculation overflowed; check price and volume scale");
            }
        }
        return new Snapshot(candlesOldestFirst.get(last).close(), candlesOldestFirst.get(last - 1).close(),
                fast[last], slow[last], fast[last - 1], rsi[last], rsi[last - 1], atr[last], adx,
                currentBands.middle(), currentBands.upper(), currentBands.lower(), previousBands.lower(),
                donchianHigh, volumeRatio);
    }

    private static void validateCandles(List<Candle> candles, int required) {
        if (candles == null || candles.size() < required) {
            throw new IllegalArgumentException("At least " + required + " chronological candles are required");
        }
        long previousTimestamp = -1;
        for (Candle candle : candles) {
            if (candle == null || candle.timestamp() < 0 || candle.timestamp() <= previousTimestamp
                    || !positiveFinite(candle.open()) || !positiveFinite(candle.high())
                    || !positiveFinite(candle.low()) || !positiveFinite(candle.close())
                    || !Double.isFinite(candle.volume()) || candle.volume() < 0
                    || candle.high() < Math.max(candle.open(), candle.close())
                    || candle.low() > Math.min(candle.open(), candle.close())) {
                throw new IllegalArgumentException("Candles must have ordered unique timestamps and valid finite OHLCV");
            }
            previousTimestamp = candle.timestamp();
        }
    }

    private static boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static double[] ema(List<Candle> candles, int period) {
        double[] values = emptySeries(candles.size());
        double average = 0;
        for (int i = 0; i < period; i++) {
            average += (candles.get(i).close() - average) / (i + 1);
        }
        values[period - 1] = average;
        double alpha = 2.0 / (period + 1.0);
        for (int i = period; i < candles.size(); i++) {
            average += alpha * (candles.get(i).close() - average);
            values[i] = average;
        }
        return values;
    }

    private static double[] rsi(List<Candle> candles, int period) {
        double[] values = emptySeries(candles.size());
        double gain = 0;
        double loss = 0;
        for (int i = 1; i < candles.size(); i++) {
            double change = candles.get(i).close() - candles.get(i - 1).close();
            double currentGain = Math.max(change, 0);
            double currentLoss = Math.max(-change, 0);
            if (i <= period) {
                gain += (currentGain - gain) / i;
                loss += (currentLoss - loss) / i;
            } else {
                gain += (currentGain - gain) / period;
                loss += (currentLoss - loss) / period;
            }
            if (i >= period) {
                values[i] = gain == 0 && loss == 0 ? 50
                        : loss == 0 ? 100 : 100 / (1 + loss / gain);
            }
        }
        return values;
    }

    private static double[] atr(List<Candle> candles, int period) {
        double[] values = emptySeries(candles.size());
        double average = 0;
        for (int i = 1; i < candles.size(); i++) {
            double range = trueRange(candles.get(i), candles.get(i - 1));
            average += (range - average) / Math.min(i, period);
            if (i >= period) {
                values[i] = average;
            }
        }
        return values;
    }

    private static double trueRange(Candle candle, Candle previous) {
        return Math.max(candle.high() - candle.low(), Math.max(
                Math.abs(candle.high() - previous.close()), Math.abs(candle.low() - previous.close())));
    }

    private static double adx(List<Candle> candles, int period) {
        double averageRange = 0;
        double plusDm = 0;
        double minusDm = 0;
        double averageDx = 0;
        int dxCount = 0;
        for (int i = 1; i < candles.size(); i++) {
            Candle current = candles.get(i);
            Candle previous = candles.get(i - 1);
            double upMove = current.high() - previous.high();
            double downMove = previous.low() - current.low();
            double positive = upMove > downMove && upMove > 0 ? upMove : 0;
            double negative = downMove > upMove && downMove > 0 ? downMove : 0;
            int divisor = Math.min(i, period);
            averageRange += (trueRange(current, previous) - averageRange) / divisor;
            plusDm += (positive - plusDm) / divisor;
            minusDm += (negative - minusDm) / divisor;
            if (i >= period) {
                // Dividing both directional movements by ATR cancels in DX. Scaling
                // first avoids overflow when otherwise-valid prices are unusually large.
                double scale = Math.max(plusDm, minusDm);
                double plus = scale == 0 || averageRange == 0 ? 0 : plusDm / scale;
                double minus = scale == 0 || averageRange == 0 ? 0 : minusDm / scale;
                double dx = plus + minus == 0 ? 0 : 100 * Math.abs(plus - minus) / (plus + minus);
                dxCount++;
                averageDx += (dx - averageDx) / Math.min(dxCount, period);
            }
        }
        return averageDx;
    }

    private record Bands(double middle, double upper, double lower) {}

    private static Bands bands(List<Candle> candles, int end, int period, double deviations) {
        double mean = 0;
        int start = end - period + 1;
        for (int i = start; i <= end; i++) {
            mean += (candles.get(i).close() - mean) / (i - start + 1);
        }
        double deviation = 0;
        double rootCount = Math.sqrt(period);
        for (int i = start; i <= end; i++) {
            deviation = Math.hypot(deviation, (candles.get(i).close() - mean) / rootCount);
        }
        return new Bands(mean, mean + deviations * deviation, mean - deviations * deviation);
    }

    private static double[] emptySeries(int size) {
        double[] values = new double[size];
        Arrays.fill(values, Double.NaN);
        return values;
    }
}
