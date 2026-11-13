package com.trade.trading.application.market;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.TickerResp;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TradingMarketInputsTest {
    @Test
    void prefersCurrencyEquityForFallbackAndReportedAccountEquityOverall() {
        var ticker = new TickerResp();
        ticker.setLast("20");
        var quote = new BalanceDetail();
        quote.setAvailBal("0");
        quote.setCashBal("100");
        quote.setEq("999");
        var base = new BalanceDetail();
        base.setAvailBal("0");
        base.setCashBal("0");
        base.setEq("2");
        var context = new TradingDecisionContext().setTicker(ticker).setQuoteBalance(quote).setBaseBalance(base);
        assertEquals(BigDecimal.ZERO, TradingMarketInputs.availableBalance(context.getQuoteBalance()));
        assertEquals(BigDecimal.ZERO, TradingMarketInputs.availableBalance(context.getBaseBalance()));
        assertEquals(new BigDecimal("1039"), TradingMarketInputs.estimatedEquity(context));
        var account = new AccountBalanceResp();
        account.setTotalEq("456");
        context.setAccountBalance(account);
        assertEquals(new BigDecimal("456"), TradingMarketInputs.estimatedEquity(context));
        assertEquals(BigDecimal.ZERO, TradingMarketInputs.estimatedEquity(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-50", " 0.00 ", " -50.25 "})
    void preservesReportedNonpositiveEquityDespitePositiveCurrencyBalances(String reportedEquity) {
        var context = contextWithPositiveBalances(reportedEquity);

        assertEquals(new BigDecimal(reportedEquity.trim()), TradingMarketInputs.estimatedEquity(context));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void onlyMissingReportedEquityUsesCurrencyBalanceFallback(String reportedEquity) {
        assertEquals(new BigDecimal("140"),
                TradingMarketInputs.estimatedEquity(contextWithPositiveBalances(reportedEquity)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "NaN", "Infinity"})
    void malformedReportedEquityCannotFallBackToPositiveCurrencyBalances(String reportedEquity) {
        assertThrows(IllegalStateException.class,
                () -> TradingMarketInputs.estimatedEquity(contextWithPositiveBalances(reportedEquity)));
    }

    @Test
    void frozenFundsRemainPartOfFallbackEquityButAreNotSpendable() {
        var context = contextWithPositiveBalances(null);
        context.getQuoteBalance().setAvailBal("10");
        context.getQuoteBalance().setCashBal("100");
        context.getBaseBalance().setAvailBal("0.5");
        context.getBaseBalance().setCashBal("2");

        assertEquals(new BigDecimal("140"), TradingMarketInputs.estimatedEquity(context));
        assertEquals(new BigDecimal("10"), TradingMarketInputs.availableBalance(context.getQuoteBalance()));
        assertEquals(new BigDecimal("0.5"), TradingMarketInputs.availableBalance(context.getBaseBalance()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingCurrencyEquityUsesCashThenAvailableBalance(String missing) {
        var context = contextWithPositiveBalances(null);
        context.getQuoteBalance().setEq(missing);
        context.getQuoteBalance().setCashBal("200");
        context.getBaseBalance().setEq(missing);
        context.getBaseBalance().setCashBal(missing);

        assertEquals(new BigDecimal("240"), TradingMarketInputs.estimatedEquity(context));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-50"})
    void fallbackPreservesCurrencyEquityIncludingLiabilities(String equity) {
        var context = contextWithPositiveBalances(null).setBaseBalance(null);
        context.getQuoteBalance().setCashBal("100");
        context.getQuoteBalance().setEq(equity);

        assertEquals(new BigDecimal(equity), TradingMarketInputs.estimatedEquity(context));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-50"})
    void fallbackPreservesCashWhenCurrencyEquityIsMissing(String cash) {
        var context = contextWithPositiveBalances(null).setBaseBalance(null);
        context.getQuoteBalance().setCashBal(cash);

        assertEquals(new BigDecimal(cash), TradingMarketInputs.estimatedEquity(context));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eq", "cashBal", "availBal"})
    void malformedCurrencyBalancesCannotBecomePositiveFallbackEquity(String field) {
        var context = contextWithPositiveBalances(null);
        var quote = context.getQuoteBalance();
        switch (field) {
            case "eq" -> quote.setEq("invalid");
            case "cashBal" -> quote.setCashBal("invalid");
            case "availBal" -> quote.setAvailBal("invalid");
            default -> fail("Unknown balance field");
        }

        assertThrows(IllegalStateException.class, () -> TradingMarketInputs.estimatedEquity(context));
        context.getAccountBalance().setTotalEq("456");
        assertEquals(new BigDecimal("456"), TradingMarketInputs.estimatedEquity(context));
    }

    private static TradingDecisionContext contextWithPositiveBalances(String reportedEquity) {
        var account = new AccountBalanceResp();
        account.setTotalEq(reportedEquity);
        var quote = new BalanceDetail();
        quote.setAvailBal("100");
        var base = new BalanceDetail();
        base.setAvailBal("2");
        var ticker = new TickerResp();
        ticker.setLast("20");
        return new TradingDecisionContext().setAccountBalance(account)
                .setQuoteBalance(quote).setBaseBalance(base).setTicker(ticker);
    }

    @Test
    void availableBalanceOnlyFallsBackWhenAvailabilityIsOmitted() {
        var balance = new BalanceDetail();
        balance.setCashBal("100");
        assertEquals(new BigDecimal("100"), TradingMarketInputs.availableBalance(balance));
        balance.setAvailBal("");
        assertEquals(new BigDecimal("100"), TradingMarketInputs.availableBalance(balance));
        balance.setAvailBal("0");
        assertEquals(BigDecimal.ZERO, TradingMarketInputs.availableBalance(balance));
        balance.setAvailBal("-1");
        assertEquals(BigDecimal.ZERO, TradingMarketInputs.availableBalance(balance));
        balance.setAvailBal("invalid");
        assertEquals(BigDecimal.ZERO, TradingMarketInputs.availableBalance(balance));
        balance.setAvailBal("12.5");
        assertEquals(new BigDecimal("12.5"), TradingMarketInputs.availableBalance(balance));
    }

    @Test
    void sizingFactsAreDetachedFromMutableProviderResponses() {
        var balance = new BalanceDetail();
        balance.setAvailBal("12.50");
        var ticker = new TickerResp();
        ticker.setLast("100");
        var instrument = new InstrumentInfoResp();
        instrument.setMinSz("0.01");
        instrument.setLotSz("0.001");
        var facts = TradingMarketInputs.sizingFacts(new TradingDecisionContext()
                .setQuoteBalance(balance).setTicker(ticker).setInstrument(instrument));
        balance.setAvailBal("0");
        ticker.setLast("200");
        instrument.setMinSz("10");
        assertEquals(new BigDecimal("12.50"), facts.availableQuote());
        assertEquals(new BigDecimal("100"), facts.lastPrice());
        assertEquals(new BigDecimal("0.01"), facts.instrument().minSize());
        assertEquals(BigDecimal.ZERO, facts.availableBase());
    }

    @Test
    void candleConversionPreservesOrderAndConfirmationWithoutSharingMutableValues() {
        var newest = new CandleResp();
        newest.setClose("101");
        newest.setVolCcyQuote("30");
        newest.setVolCcy("2");
        newest.setConfirm("0");
        var older = new CandleResp();
        older.setClose("100");
        older.setConfirm("1");
        var candles = TradingMarketInputs.thresholdCandles(Arrays.asList(newest, null, older));
        newest.setClose("999");
        newest.setConfirm("1");
        assertEquals(2, candles.size());
        assertEquals(new BigDecimal("101"), candles.getFirst().close());
        assertFalse(candles.getFirst().confirmed());
        assertTrue(candles.getLast().confirmed());
        assertEquals(new BigDecimal("100"), candles.getLast().close());
        assertThrows(UnsupportedOperationException.class, () -> candles.clear());
    }
}
