package com.trade.trading.strategy;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class ThresholdEventStrategyConfig implements StrategyConfig {
    private BigDecimal priceMoveTriggerPercent;
    private BigDecimal volumeSpikeMultiplier;
    private BigDecimal floatingLossTriggerPercent;
    private BigDecimal buyQuoteAmount;
    private BigDecimal sellBaseAmount;
    private BigDecimal orderSize;
    private int priceMoveWindowCandles = 5;
    private int volumeLookbackCandles = 20;
    private boolean requireConfirmedCandle = true;
}
