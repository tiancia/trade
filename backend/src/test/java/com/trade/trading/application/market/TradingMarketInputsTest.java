package com.trade.trading.application.market;

import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.CandleResp;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.TickerResp;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradingMarketInputsTest {
    @Test
    void preservesCashAndEquityFallbacksAndPrefersReportedAccountEquity() {
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
        assertEquals(new BigDecimal("100"), TradingMarketInputs.availableBalance(context.getQuoteBalance()));
        assertEquals(BigDecimal.ZERO, TradingMarketInputs.availableBalance(context.getBaseBalance()));
        assertEquals(new BigDecimal("140"), TradingMarketInputs.estimatedEquity(context));
        var account = new AccountBalanceResp();
        account.setTotalEq("456");
        context.setAccountBalance(account);
        assertEquals(new BigDecimal("456"), TradingMarketInputs.estimatedEquity(context));
        assertEquals(BigDecimal.ZERO, TradingMarketInputs.estimatedEquity(null));
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
