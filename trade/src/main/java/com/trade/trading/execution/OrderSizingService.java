package com.trade.trading.execution;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.OrderSizing;
import com.trade.trading.model.TradingDecisionContext;
import com.trade.trading.risk.RiskContext;
import com.trade.trading.support.TradingMath;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class OrderSizingService {
    private final TradingProperties properties;

    public OrderSizingService(TradingProperties properties) {
        this.properties = properties;
    }

    public OrderSizing buySize(AiTradingDecision decision, TradingDecisionContext context) {
        BigDecimal aiAmount = decision.getBuyQuoteAmountUsdt();
        BigDecimal availableQuote = available(context.getQuoteBalance());
        BigDecimal maxAmount = properties.getMaxBuyQuoteAmount();
        maxAmount = TradingMath.clamp(maxAmount, maxSingleOpenQuoteAmount(context));
        BigDecimal amount = TradingMath.clamp(TradingMath.clamp(aiAmount, maxAmount), availableQuote);
        amount = amount.setScale(properties.getQuoteAmountScale(), RoundingMode.DOWN);

        InstrumentInfoResp instrument = context.getInstrument();
        BigDecimal maxMarketAmount = TradingMath.decimal(instrument.getMaxMktAmt());
        amount = TradingMath.clamp(amount, maxMarketAmount).setScale(properties.getQuoteAmountScale(), RoundingMode.DOWN);

        if (amount.signum() <= 0) {
            return OrderSizing.skipped("BUY skipped: amount is zero after caps");
        }

        BigDecimal lastPrice = lastPrice(context.getTicker());
        BigDecimal minBaseSize = TradingMath.decimal(instrument.getMinSz());
        if (lastPrice.signum() > 0 && minBaseSize.signum() > 0) {
            BigDecimal estimatedBase = amount.divide(lastPrice, 18, RoundingMode.DOWN);
            if (estimatedBase.compareTo(minBaseSize) < 0) {
                return OrderSizing.skipped("BUY skipped: estimated BTC amount is below OKX minSz");
            }
        }

        return OrderSizing.executable(TradingMath.plain(amount));
    }

    public OrderSizing sellSize(AiTradingDecision decision, TradingDecisionContext context) {
        BigDecimal aiAmount = decision.getSellBaseAmountBtc();
        BigDecimal availableBase = available(context.getBaseBalance());
        BigDecimal maxByRatio = availableBase.multiply(properties.getMaxSellPositionRatio());
        BigDecimal amount = TradingMath.clamp(TradingMath.clamp(aiAmount, maxByRatio), availableBase);

        InstrumentInfoResp instrument = context.getInstrument();
        BigDecimal lotSize = TradingMath.decimal(instrument.getLotSz());
        amount = TradingMath.roundDownToStep(amount, lotSize);

        BigDecimal maxMarketSize = TradingMath.decimal(instrument.getMaxMktSz());
        amount = TradingMath.clamp(amount, maxMarketSize);
        amount = TradingMath.roundDownToStep(amount, lotSize);

        BigDecimal minSize = TradingMath.decimal(instrument.getMinSz());
        if (amount.signum() <= 0) {
            return OrderSizing.skipped("SELL skipped: amount is zero after caps");
        }
        if (minSize.signum() > 0 && amount.compareTo(minSize) < 0) {
            return OrderSizing.skipped("SELL skipped: BTC amount is below OKX minSz");
        }

        return OrderSizing.executable(TradingMath.plain(amount));
    }

    public OrderSizing derivativeSize(AiTradingDecision decision, TradingDecisionContext context) {
        BigDecimal amount = TradingMath.clamp(decision.getOrderSize(), properties.getMaxDerivativeOrderSize());

        InstrumentInfoResp instrument = context.getInstrument();
        BigDecimal lotSize = TradingMath.decimal(instrument.getLotSz());
        amount = TradingMath.roundDownToStep(amount, lotSize);

        BigDecimal maxMarketSize = TradingMath.decimal(instrument.getMaxMktSz());
        amount = TradingMath.clamp(amount, maxMarketSize);
        amount = TradingMath.roundDownToStep(amount, lotSize);

        BigDecimal minSize = TradingMath.decimal(instrument.getMinSz());
        if (amount.signum() <= 0) {
            return OrderSizing.skipped(decision.getAction() + " skipped: orderSize is zero after caps");
        }
        if (minSize.signum() > 0 && amount.compareTo(minSize) < 0) {
            return OrderSizing.skipped(decision.getAction() + " skipped: orderSize is below OKX minSz");
        }

        return OrderSizing.executable(TradingMath.plain(amount));
    }

    private static BigDecimal available(BalanceDetail detail) {
        if (detail == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal availBal = TradingMath.decimal(detail.getAvailBal());
        if (availBal.signum() > 0) {
            return availBal;
        }
        return TradingMath.decimal(detail.getCashBal());
    }

    private static BigDecimal lastPrice(TickerResp ticker) {
        if (ticker == null) {
            return BigDecimal.ZERO;
        }
        return TradingMath.decimal(ticker.getLast());
    }

    private BigDecimal maxSingleOpenQuoteAmount(TradingDecisionContext context) {
        TradingProperties.RiskProperties riskProperties = properties.getRisk();
        if (riskProperties == null || !riskProperties.isEnabled()) {
            return BigDecimal.ZERO;
        }

        BigDecimal ratio = riskProperties.getMaxSingleOpenEquityRatio();
        BigDecimal equity = RiskContext.estimateEquity(context);
        if (ratio == null || ratio.signum() <= 0 || equity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return equity.multiply(ratio);
    }
}
