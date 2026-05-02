package com.trade.trading.execution;

import com.trade.client.okx.dto.BalanceDetail;
import com.trade.client.okx.dto.InstrumentInfoResp;
import com.trade.client.okx.dto.TickerResp;
import com.trade.trading.config.TradingProperties;
import com.trade.trading.model.AiTradingDecision;
import com.trade.trading.model.OrderSizing;
import com.trade.trading.model.TradingAction;
import com.trade.trading.model.TradingDecisionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderSizingServiceTest {

    @Test
    void buySizeIsCappedBySystemAndAvailableQuote() {
        TradingProperties properties = new TradingProperties();
        properties.setMaxBuyQuoteAmount(new BigDecimal("100"));
        OrderSizingService sizingService = new OrderSizingService(properties);

        AiTradingDecision decision = new AiTradingDecision()
                .setAction(TradingAction.BUY)
                .setBuyQuoteAmountUsdt(new BigDecimal("500"));
        TradingDecisionContext context = context("50000", "0", "80", "0.00001", "0.00000001");

        OrderSizing sizing = sizingService.buySize(decision, context);

        assertTrue(sizing.isExecutable());
        assertEquals("80", sizing.getSize());
    }

    @Test
    void buySizeSkipsWhenEstimatedBaseIsBelowMinSize() {
        TradingProperties properties = new TradingProperties();
        OrderSizingService sizingService = new OrderSizingService(properties);

        AiTradingDecision decision = new AiTradingDecision()
                .setAction(TradingAction.BUY)
                .setBuyQuoteAmountUsdt(new BigDecimal("0.10"));
        TradingDecisionContext context = context("50000", "0", "100", "0.00001", "0.00000001");

        OrderSizing sizing = sizingService.buySize(decision, context);

        assertFalse(sizing.isExecutable());
        assertEquals("BUY skipped: estimated BTC amount is below OKX minSz", sizing.getSkipReason());
    }

    @Test
    void sellSizeIsCappedByAvailableBaseAndRoundedToLotSize() {
        TradingProperties properties = new TradingProperties();
        OrderSizingService sizingService = new OrderSizingService(properties);

        AiTradingDecision decision = new AiTradingDecision()
                .setAction(TradingAction.SELL)
                .setSellBaseAmountBtc(new BigDecimal("2"));
        TradingDecisionContext context = context("50000", "0.123456", "100", "0.00001", "0.0001");

        OrderSizing sizing = sizingService.sellSize(decision, context);

        assertTrue(sizing.isExecutable());
        assertEquals("0.1234", sizing.getSize());
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
