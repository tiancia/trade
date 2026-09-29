package com.trade.trading.application.market;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.common.support.TradingMath;
import com.trade.trading.domain.order.OrderSizingRules;
import com.trade.trading.domain.risk.AccountValuation;
import com.trade.trading.domain.strategy.ThresholdDecisionPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Converts provider values into the existing, provider-independent rule inputs. */
public final class TradingMarketInputs {
    private TradingMarketInputs() {}

    public static BigDecimal lastPrice(TradingDecisionContext context) {
        return context == null || context.getTicker() == null
                ? BigDecimal.ZERO : TradingMath.decimal(context.getTicker().getLast());
    }

    public static BigDecimal availableBalance(BalanceDetail detail) {
        return detail == null ? BigDecimal.ZERO : AccountValuation.available(
                TradingMath.decimal(detail.getAvailBal()), TradingMath.decimal(detail.getCashBal()));
    }

    public static BigDecimal estimatedEquity(TradingDecisionContext context) {
        if (context == null) { return BigDecimal.ZERO; }
        var account = context.getAccountBalance();
        return AccountValuation.equity(TradingMath.decimal(account == null ? null : account.getTotalEq()),
                balanceAmount(context.getQuoteBalance()), balanceAmount(context.getBaseBalance()), lastPrice(context));
    }

    public static OrderSizingRules.SizingFacts sizingFacts(TradingDecisionContext context) {
        var instrument = context.getInstrument();
        return new OrderSizingRules.SizingFacts(
                availableBalance(context.getQuoteBalance()), availableBalance(context.getBaseBalance()), lastPrice(context),
                new OrderSizingRules.InstrumentLimits(TradingMath.decimal(instrument.getMaxMktAmt()),
                        TradingMath.decimal(instrument.getMinSz()), TradingMath.decimal(instrument.getLotSz()),
                        TradingMath.decimal(instrument.getMaxMktSz())));
    }

    public static List<ThresholdDecisionPolicy.Candle> thresholdCandles(List<CandleResp> newestFirst) {
        return newestFirst.stream().filter(Objects::nonNull)
                .map(candle -> new ThresholdDecisionPolicy.Candle(TradingMath.decimal(candle.getClose()),
                        TradingMath.decimal(candle.getVolCcyQuote()), TradingMath.decimal(candle.getVolCcy()),
                        "1".equals(candle.getConfirm())))
                .toList();
    }

    private static BigDecimal balanceAmount(BalanceDetail detail) {
        return detail == null ? BigDecimal.ZERO : AccountValuation.balance(
                TradingMath.decimal(detail.getAvailBal()), TradingMath.decimal(detail.getCashBal()),
                TradingMath.decimal(detail.getEq()));
    }
}
