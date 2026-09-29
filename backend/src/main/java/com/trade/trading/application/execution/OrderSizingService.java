package com.trade.trading.application.execution;

import com.trade.trading.domain.risk.AccountValuation;
import com.trade.trading.application.market.TradingMarketInputs;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.domain.order.OrderSizingRules;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

/** Maps configuration and provider data to the pure order-sizing policy. */
@Component
public class OrderSizingService {
    private final TradingProperties properties;

    public OrderSizingService(TradingProperties properties) {
        this.properties = properties;
    }

    public OrderSizing buySize(StrategyDecision decision, TradingDecisionContext context) {
        return rules(context).buySize(decision, TradingMarketInputs.sizingFacts(context));
    }

    public OrderSizing sellSize(StrategyDecision decision, TradingDecisionContext context) {
        return rules(context).sellSize(decision, TradingMarketInputs.sizingFacts(context));
    }

    public OrderSizing derivativeSize(StrategyDecision decision, TradingDecisionContext context) {
        return rules(context).derivativeSize(decision, TradingMarketInputs.sizingFacts(context));
    }

    private OrderSizingRules rules(TradingDecisionContext context) {
        return new OrderSizingRules(new OrderSizingRules.Limits(
                properties.getMaxBuyQuoteAmount(), properties.getQuoteAmountScale(),
                properties.getMaxSellPositionRatio(), properties.getMaxDerivativeOrderSize(),
                maxSingleOpenQuoteAmount(context)));
    }

    private BigDecimal maxSingleOpenQuoteAmount(TradingDecisionContext context) {
        TradingProperties.RiskProperties risk = properties.getRisk();
        return risk == null ? BigDecimal.ZERO : AccountValuation.singleOpenLimit(
                risk.isEnabled(), risk.getMaxSingleOpenEquityRatio(), TradingMarketInputs.estimatedEquity(context));
    }
}
