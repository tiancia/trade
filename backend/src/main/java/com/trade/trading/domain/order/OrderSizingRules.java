package com.trade.trading.domain.order;

import com.trade.common.support.TradingMath;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.model.StrategyDecision;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Order caps, lot rounding, and minimum-size rules with resolved numeric inputs. */
public final class OrderSizingRules {
    private final Limits limits;

    public OrderSizingRules(Limits limits) {
        this.limits = limits;
    }

    public record Limits(BigDecimal maxBuyQuoteAmount, int quoteAmountScale,
                         BigDecimal maxSellPositionRatio, BigDecimal maxDerivativeOrderSize,
                         BigDecimal maxSingleOpenQuoteAmount) {}

    public record InstrumentLimits(BigDecimal maxMarketAmount, BigDecimal minSize,
                                   BigDecimal lotSize, BigDecimal maxMarketSize) {}

    public record SizingFacts(BigDecimal availableQuote, BigDecimal availableBase,
                              BigDecimal lastPrice, InstrumentLimits instrument) {}

    public OrderSizing buySize(StrategyDecision decision, SizingFacts context) {
        BigDecimal requestedAmount = decision.getBuyQuoteAmount();
        BigDecimal availableQuote = context.availableQuote();
        BigDecimal maxAmount = limits.maxBuyQuoteAmount();
        // Optional equity-ratio cap limits a single new open even if the AI
        // asks for less than the absolute maxBuyQuoteAmount.
        maxAmount = TradingMath.clamp(maxAmount, limits.maxSingleOpenQuoteAmount());
        BigDecimal amount = TradingMath.clamp(TradingMath.clamp(requestedAmount, maxAmount), availableQuote);
        amount = amount.setScale(limits.quoteAmountScale(), RoundingMode.DOWN);

        InstrumentLimits instrument = context.instrument();
        BigDecimal maxMarketAmount = instrument.maxMarketAmount();
        amount = TradingMath.clamp(amount, maxMarketAmount).setScale(limits.quoteAmountScale(), RoundingMode.DOWN);

        if (amount.signum() <= 0) {
            return OrderSizing.skipped("BUY skipped: amount is zero after caps");
        }

        BigDecimal lastPrice = context.lastPrice();
        BigDecimal minBaseSize = instrument.minSize();
        if (lastPrice.signum() > 0 && minBaseSize.signum() > 0) {
            BigDecimal estimatedBase = amount.divide(lastPrice, 18, RoundingMode.DOWN);
            if (estimatedBase.compareTo(minBaseSize) < 0) {
                return OrderSizing.skipped("BUY skipped: estimated BTC amount is below OKX minSz");
            }
        }

        return OrderSizing.executable(TradingMath.plain(amount));
    }

    public OrderSizing sellSize(StrategyDecision decision, SizingFacts context) {
        BigDecimal requestedAmount = decision.getSellBaseAmount();
        BigDecimal availableBase = context.availableBase();
        BigDecimal maxByRatio = availableBase.multiply(limits.maxSellPositionRatio());
        BigDecimal amount = TradingMath.clamp(TradingMath.clamp(requestedAmount, maxByRatio), availableBase);

        InstrumentLimits instrument = context.instrument();
        BigDecimal lotSize = instrument.lotSize();
        amount = TradingMath.roundDownToStep(amount, lotSize);

        BigDecimal maxMarketSize = instrument.maxMarketSize();
        amount = TradingMath.clamp(amount, maxMarketSize);
        amount = TradingMath.roundDownToStep(amount, lotSize);

        BigDecimal minSize = instrument.minSize();
        if (amount.signum() <= 0) {
            return OrderSizing.skipped("SELL skipped: amount is zero after caps");
        }
        if (minSize.signum() > 0 && amount.compareTo(minSize) < 0) {
            return OrderSizing.skipped("SELL skipped: BTC amount is below OKX minSz");
        }

        return OrderSizing.executable(TradingMath.plain(amount));
    }

    public OrderSizing derivativeSize(StrategyDecision decision, SizingFacts context) {
        BigDecimal amount = TradingMath.clamp(decision.getOrderSize(), limits.maxDerivativeOrderSize());

        InstrumentLimits instrument = context.instrument();
        BigDecimal lotSize = instrument.lotSize();
        amount = TradingMath.roundDownToStep(amount, lotSize);

        BigDecimal maxMarketSize = instrument.maxMarketSize();
        amount = TradingMath.clamp(amount, maxMarketSize);
        amount = TradingMath.roundDownToStep(amount, lotSize);

        BigDecimal minSize = instrument.minSize();
        if (amount.signum() <= 0) {
            return OrderSizing.skipped(decision.getAction() + " skipped: orderSize is zero after caps");
        }
        if (minSize.signum() > 0 && amount.compareTo(minSize) < 0) {
            return OrderSizing.skipped(decision.getAction() + " skipped: orderSize is below OKX minSz");
        }

        return OrderSizing.executable(TradingMath.plain(amount));
    }

}
