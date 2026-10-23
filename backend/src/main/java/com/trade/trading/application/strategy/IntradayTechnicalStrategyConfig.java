package com.trade.trading.application.strategy;

import com.trade.trading.domain.strategy.IntradayTechnicalPolicy;
import com.trade.trading.domain.strategy.TechnicalIndicators;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** Candidate settings for closed-candle, spot-long intraday setups. */
@Data
@Accessors(chain = true)
public class IntradayTechnicalStrategyConfig implements StrategyConfig {
    private IntradayTechnicalPolicy.Mode mode = IntradayTechnicalPolicy.Mode.TREND_PULLBACK;
    private int emaFastPeriod = 9;
    private int emaSlowPeriod = 21;
    private int rsiPeriod = 14;
    private int atrPeriod = 14;
    private int adxPeriod = 14;
    private int bollingerPeriod = 20;
    private BigDecimal bollingerDeviations = new BigDecimal("2");
    private int breakoutPeriod = 20;
    private int volumePeriod = 20;
    private BigDecimal trendMinAdx = new BigDecimal("18");
    private BigDecimal rangeMaxAdx = new BigDecimal("20");
    private BigDecimal entryRsiMin = new BigDecimal("45");
    private BigDecimal entryRsiMax = new BigDecimal("68");
    private BigDecimal minVolumeRatio = BigDecimal.ONE;
    private BigDecimal minAtrPercent = new BigDecimal("0.0015");
    private BigDecimal maxAtrPercent = new BigDecimal("0.03");
    private BigDecimal maxEntryExtensionAtr = new BigDecimal("2.5");
    private BigDecimal stopLossPercent = new BigDecimal("0.006");
    private BigDecimal takeProfitPercent = new BigDecimal("0.018");
    private BigDecimal estimatedSlippageRate = new BigDecimal("0.0002");
    private BigDecimal minNetRewardPercent = new BigDecimal("0.003");
    private BigDecimal buyQuoteAmount = new BigDecimal("10");

    public int warmupCandles() {
        return Math.max(emaSlowPeriod * 3, TechnicalIndicators.requiredCandles(emaFastPeriod,
                emaSlowPeriod, rsiPeriod, atrPeriod, adxPeriod, bollingerPeriod, breakoutPeriod, volumePeriod));
    }

    public IntradayTechnicalPolicy.Settings settings() {
        return new IntradayTechnicalPolicy.Settings(mode, trendMinAdx.doubleValue(), rangeMaxAdx.doubleValue(),
                entryRsiMin.doubleValue(), entryRsiMax.doubleValue(), minVolumeRatio.doubleValue(),
                minAtrPercent.doubleValue(), maxAtrPercent.doubleValue(), maxEntryExtensionAtr.doubleValue(),
                stopLossPercent.doubleValue(), takeProfitPercent.doubleValue(), minNetRewardPercent.doubleValue());
    }
}
