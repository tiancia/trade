package com.trade.trading.application.execution;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.AccountBalanceResp;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.domain.model.OrderSizing;
import com.trade.trading.domain.model.StrategyDecision;
import com.trade.trading.domain.model.TradingAction;
import com.trade.trading.application.market.TradingDecisionContext;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderSizingServiceTest {

    @Test
    void zeroSellRatioCannotAuthorizeEvenASmallSale() {
        TradingProperties properties = new TradingProperties();
        properties.setMaxSellPositionRatio(BigDecimal.ZERO);
        var sizing = new OrderSizingService(properties);
        var context = context("50000", "1", "0", "0.0001", "0.0001");
        var sell = StrategyDecision.sell("test", new BigDecimal("0.01"), "exit");

        assertFalse(sizing.sellSize(sell, context).isExecutable());
        assertFalse(sizing.sellSize(sell, context, BigDecimal.ONE).isExecutable());
    }

    @Test
    void buySizeIsCappedBySystemAndAvailableQuote() {
        TradingProperties properties = new TradingProperties();
        properties.getRisk().setEnabled(false);
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        OrderSizingService sizingService = new OrderSizingService(properties);

        StrategyDecision decision = new StrategyDecision()
                .setAction(TradingAction.BUY)
                .setBuyQuoteAmount(new BigDecimal("500"));
        TradingDecisionContext context = context("50000", "0", "80", "0.00001", "0.00000001");

        OrderSizing sizing = sizingService.buySize(decision, context);

        assertTrue(sizing.isExecutable());
        assertEquals("80", sizing.getSize());
    }

    @Test
    void buySkipsWhenCashExistsButAvailableQuoteIsZero() {
        TradingProperties properties = new TradingProperties();
        properties.getRisk().setEnabled(false);
        TradingDecisionContext context = context("50000", "0", "0", "0.00001", "0.00000001");
        context.getQuoteBalance().setCashBal("100");

        OrderSizing sizing = new OrderSizingService(properties).buySize(
                StrategyDecision.buy("test", new BigDecimal("10"), "entry"), context);

        assertFalse(sizing.isExecutable());
        assertEquals("BUY skipped: no available quote balance", sizing.getSkipReason());
    }

    @Test
    void fallbackEquityIncludesFrozenCashButBuyStillCannotSpendIt() {
        TradingProperties properties = new TradingProperties();
        properties.setMaxBuyQuoteAmount(new BigDecimal("1000"));
        properties.getRisk().setMaxSingleOpenEquityRatio(new BigDecimal("0.10"));
        TradingDecisionContext context = context("50000", "0", "200", "0.00001", "0.00000001");
        context.getQuoteBalance().setCashBal("1000");
        OrderSizingService sizing = new OrderSizingService(properties);
        StrategyDecision decision = StrategyDecision.buy("test", new BigDecimal("500"), "entry");

        assertEquals("100", sizing.buySize(decision, context).getSize());
        context.getQuoteBalance().setAvailBal("50");
        assertEquals("50", sizing.buySize(decision, context).getSize());
        context.getQuoteBalance().setAvailBal("0");
        assertFalse(sizing.buySize(decision, context).isExecutable());
    }

    @Test
    void buySizeSkipsWhenEstimatedBaseIsBelowMinSize() {
        TradingProperties properties = new TradingProperties();
        properties.getRisk().setEnabled(false);
        OrderSizingService sizingService = new OrderSizingService(properties);

        StrategyDecision decision = new StrategyDecision()
                .setAction(TradingAction.BUY)
                .setBuyQuoteAmount(new BigDecimal("0.10"));
        TradingDecisionContext context = context("50000", "0", "100", "0.00001", "0.00000001");

        OrderSizing sizing = sizingService.buySize(decision, context);

        assertFalse(sizing.isExecutable());
        assertEquals("BUY skipped: estimated BTC amount is below OKX minSz", sizing.getSkipReason());
    }

    @Test
    void sellSizeIsCappedByAvailableBaseAndRoundedToLotSize() {
        TradingProperties properties = new TradingProperties();
        properties.getRisk().setEnabled(false);
        OrderSizingService sizingService = new OrderSizingService(properties);

        StrategyDecision decision = new StrategyDecision()
                .setAction(TradingAction.SELL)
                .setSellBaseAmount(new BigDecimal("2"));
        TradingDecisionContext context = context("50000", "0.123456", "100", "0.00001", "0.0001");

        OrderSizing sizing = sizingService.sellSize(decision, context);

        assertTrue(sizing.isExecutable());
        assertEquals("0.1234", sizing.getSize());
    }

    @Test
    void derivativeSizeIsCappedAndRoundedToLotSize() {
        TradingProperties properties = new TradingProperties();
        properties.getRisk().setEnabled(false);
        properties.setMaxDerivativeOrderSize(new BigDecimal("3.7"));
        OrderSizingService sizingService = new OrderSizingService(properties);

        StrategyDecision decision = new StrategyDecision()
                .setAction(TradingAction.OPEN_LONG)
                .setOrderSize(new BigDecimal("10"));
        TradingDecisionContext context = context("50000", "0", "100", "1", "0.5");

        OrderSizing sizing = sizingService.derivativeSize(decision, context);

        assertTrue(sizing.isExecutable());
        assertEquals("3.5", sizing.getSize());
    }

    @Test
    void buySizeIsCappedBySingleOpenEquityRatio() {
        TradingProperties properties = new TradingProperties();
        properties.setMaxBuyQuoteAmount(new BigDecimal("1000"));
        properties.getRisk().setMaxSingleOpenEquityRatio(new BigDecimal("0.10"));
        OrderSizingService sizingService = new OrderSizingService(properties);

        StrategyDecision decision = new StrategyDecision()
                .setAction(TradingAction.BUY)
                .setBuyQuoteAmount(new BigDecimal("500"));
        TradingDecisionContext context = context("50000", "0", "800", "0.00001", "0.00000001");

        OrderSizing sizing = sizingService.buySize(decision, context);

        assertTrue(sizing.isExecutable());
        assertEquals("80", sizing.getSize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-100"})
    void nonpositiveEquityProvidesNoBuyAllowanceButDoesNotBlockSelling(String equity) {
        TradingProperties properties = new TradingProperties();
        properties.getRisk().setMaxSingleOpenEquityRatio(new BigDecimal("0.10"));
        TradingDecisionContext context = context("50000", "0.01", "1000", "0.00001", "0.0001");
        AccountBalanceResp account = new AccountBalanceResp();
        account.setTotalEq(equity);
        context.setAccountBalance(account);
        OrderSizingService sizingService = new OrderSizingService(properties);

        OrderSizing buy = sizingService.buySize(StrategyDecision.buy("test", new BigDecimal("100"), "entry"), context);
        OrderSizing sell = sizingService.sellSize(StrategyDecision.sell("test", new BigDecimal("0.01"), "exit"), context);

        assertFalse(buy.isExecutable());
        assertEquals("BUY skipped: amount is zero after caps", buy.getSkipReason());
        assertTrue(sell.isExecutable());
        assertEquals("0.01", sell.getSize());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void equityRatioCapStillAppliesWithoutAnAbsoluteBuyCap(String absoluteCap) {
        TradingProperties properties = new TradingProperties();
        properties.setMaxBuyQuoteAmount(absoluteCap == null ? null : new BigDecimal(absoluteCap));
        properties.getRisk().setMaxSingleOpenEquityRatio(new BigDecimal("0.10"));

        OrderSizing sizing = new OrderSizingService(properties).buySize(
                StrategyDecision.buy("test", new BigDecimal("500"), "entry"),
                context("50000", "0", "800", "0.00001", "0.00000001"));

        assertTrue(sizing.isExecutable());
        assertEquals("80", sizing.getSize());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-0.1"})
    void disabledEquityRatioStillUsesTheAbsoluteBuyCap(String ratio) {
        TradingProperties properties = new TradingProperties();
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        properties.getRisk().setMaxSingleOpenEquityRatio(ratio == null ? null : new BigDecimal(ratio));

        OrderSizing sizing = new OrderSizingService(properties).buySize(
                StrategyDecision.buy("test", new BigDecimal("500"), "entry"),
                context("50000", "0", "800", "0.00001", "0.00000001"));

        assertTrue(sizing.isExecutable());
        assertEquals("100", sizing.getSize());
    }

    private static TradingDecisionContext context(
            String last,
            String baseAvail,
            String quoteAvail,
            String minSize,
            String lotSize
    ) {
        TickerResp ticker = new TickerResp();
        ticker.setLast(last);

        BalanceDetail base = new BalanceDetail();
        base.setCcy("BTC");
        base.setAvailBal(baseAvail);

        BalanceDetail quote = new BalanceDetail();
        quote.setCcy("USDT");
        quote.setAvailBal(quoteAvail);

        InstrumentInfoResp instrument = new InstrumentInfoResp();
        instrument.setMinSz(minSize);
        instrument.setLotSz(lotSize);
        instrument.setMaxMktAmt("1000");
        instrument.setMaxMktSz("10");

        return new TradingDecisionContext()
                .setTicker(ticker)
                .setBaseBalance(base)
                .setQuoteBalance(quote)
                .setInstrument(instrument);
    }
}
