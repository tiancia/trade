package com.trade.trading.application.execution;

import com.trade.trading.domain.risk.AccountValuation;
import com.trade.trading.application.market.TradingMarketInputs;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.order.OrderSizingRules;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.port.UsdIndexPriceSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/** Maps configuration and provider data to the pure order-sizing policy. */
@Component
public class OrderSizingService {
    private final TradingProperties properties;
    private final UsdIndexPriceSource prices;

    /** Historical runs supply no current exchange limits or FX quotes. */
    public OrderSizingService(TradingProperties properties) {
        this(properties, currency -> { throw new IllegalStateException("Missing USD index price for " + currency); });
    }

    @Autowired
    public OrderSizingService(TradingProperties properties, UsdIndexPriceSource prices) {
        this.properties = properties;
        this.prices = prices;
    }

    public OrderSizing buySize(StrategyDecision decision, TradingDecisionContext context) {
        return rules(context).buySize(decision, spotFacts(context));
    }

    public OrderSizing sellSize(StrategyDecision decision, TradingDecisionContext context) {
        return rules(context).sellSize(decision, spotFacts(context));
    }

    public OrderSizing sellSize(StrategyDecision decision, TradingDecisionContext context, BigDecimal managedQuantity) {
        return rules(context).sellSize(decision, spotFacts(context), managedQuantity);
    }

    public OrderSizing derivativeSize(StrategyDecision decision, TradingDecisionContext context) {
        return rules(context).derivativeSize(decision, TradingMarketInputs.sizingFacts(context, null));
    }

    OrderSizing spotSize(StrategyDecision decision, TradingDecisionContext context, OrderSizingRules.SizingFacts facts) {
        var rules = rules(context);
        return decision.getAction() == TradingAction.BUY ? rules.buySize(decision, facts) : rules.sellSize(decision, facts);
    }

    OrderSizingRules.SizingFacts spotFacts(TradingDecisionContext context) {
        var instrument = context.getInstrument();
        BigDecimal usd = optionalCap(instrument.getMaxMktAmt());
        BigDecimal usdt = optionalCap(instrument.getMaxMktSz());
        String quote = instrument.getQuoteCcy() == null || instrument.getQuoteCcy().isBlank()
                ? properties.getQuoteCcy() : instrument.getQuoteCcy();
        return TradingMarketInputs.sizingFacts(context, quoteLimit(usd, usdt, quote.toUpperCase(Locale.ROOT)));
    }

    private BigDecimal quoteLimit(BigDecimal usd, BigDecimal usdt, String quote) {
        if (usd == null && (usdt == null || "USDT".equals(quote))) return usdt;
        BigDecimal quoteUsd = "USD".equals(quote) ? BigDecimal.ONE : prices.usdPrice(quote);
        if (usdt != null) {
            BigDecimal usdtUsd = "USDT".equals(quote) ? quoteUsd : prices.usdPrice("USDT");
            BigDecimal converted = usdt.multiply(usdtUsd);
            usd = usd == null ? converted : usd.min(converted);
        }
        return usd.divide(quoteUsd, new MathContext(34, RoundingMode.DOWN));
    }

    private static BigDecimal optionalCap(String text) {
        if (text == null || text.isBlank()) return null;
        BigDecimal value = new BigDecimal(text);
        if (value.signum() < 0) throw new IllegalArgumentException("Negative OKX market order limit");
        return value.signum() == 0 ? null : value;
    }

    private OrderSizingRules rules(TradingDecisionContext context) {
        return new OrderSizingRules(new OrderSizingRules.Limits(
                properties.getMaxBuyQuoteAmount(), properties.getQuoteAmountScale(),
                properties.getMaxSellPositionRatio(), properties.getMaxDerivativeOrderSize(),
                maxSingleOpenQuoteAmount(context)));
    }

    private BigDecimal maxSingleOpenQuoteAmount(TradingDecisionContext context) {
        TradingProperties.RiskProperties risk = properties.getRisk();
        return risk == null ? null : AccountValuation.singleOpenLimit(
                risk.isEnabled(), risk.getMaxSingleOpenEquityRatio(), TradingMarketInputs.estimatedEquity(context));
    }
}
