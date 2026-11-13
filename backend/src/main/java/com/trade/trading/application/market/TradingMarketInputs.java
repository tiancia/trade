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
        if (detail == null) {
            return BigDecimal.ZERO;
        }
        // A reported zero can mean the cash is frozen by other orders. Only
        // fall back when the provider omitted availability altogether.
        String available = detail.getAvailBal();
        return TradingMath.decimal(available == null || available.isBlank()
                ? detail.getCashBal() : available).max(BigDecimal.ZERO);
    }

    public static BigDecimal estimatedEquity(TradingDecisionContext context) {
        if (context == null) { return BigDecimal.ZERO; }
        var account = context.getAccountBalance();
        BigDecimal totalEquity = reportedDecimal(account == null ? null : account.getTotalEq(), "totalEq");
        // An authoritative total does not depend on the optional fallback fields.
        if (totalEquity != null) return totalEquity;
        return AccountValuation.equity(null,
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
                reportedDecimal(detail.getAvailBal(), "availBal"), reportedDecimal(detail.getCashBal(), "cashBal"),
                reportedDecimal(detail.getEq(), "eq"));
    }

    private static BigDecimal reportedDecimal(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException invalid) {
            // Invalid financial data cannot silently become a zero or positive fallback.
            throw new IllegalStateException("Account snapshot has invalid " + field, invalid);
        }
    }
}
