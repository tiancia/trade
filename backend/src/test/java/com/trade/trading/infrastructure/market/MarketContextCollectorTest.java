package com.trade.trading.infrastructure.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.*;
import com.trade.trading.application.event.TradingEventPublishResult;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.model.TradingTrigger;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.persistence.TradingStateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketContextCollectorTest {
    @Test
    void paperUsesVirtualBalancesInEvaluationAndAuditWithoutPrivateExchangeCalls() {
        TradingProperties properties = new TradingProperties();
        OkxApi api = marketApi();
        TradingStateRepository state = mock(TradingStateRepository.class);
        when(state.getState()).thenReturn(new TradingState().setTrackedBaseAmount(new BigDecimal("99")));
        PaperAccountService paper = mock(PaperAccountService.class);
        when(paper.enrichContext(any())).thenAnswer(invocation -> {
            var context = invocation.<com.trade.trading.application.market.TradingDecisionContext>getArgument(0);
            var account = new AccountBalanceResp();
            account.setTotalEq("1000");
            var cash = new BalanceDetail();
            cash.setCcy("USDT");
            cash.setAvailBal("1000");
            context.setAccountBalance(account).setQuoteBalance(cash)
                    .setTradingState(new TradingState());
            return context;
        });
        var collector = new MarketContextCollector(api, properties, state,
                event -> TradingEventPublishResult.ACCEPTED, paper);

        var context = collector.collect(TradingTrigger.scheduled());

        assertEquals("1000", context.getQuoteBalance().getAvailBal());
        assertEquals(BigDecimal.ZERO, context.getTradingState().getTrackedBaseAmount());
        assertSame(context.getAccountBalance(), context.getAiParameters().get("accountSummary"));
        assertSame(context.getQuoteBalance(), ((Map<?, ?>) context.getAiParameters().get("balances")).get("USDT"));
        verify(api).getPublicInstrumentInfo(any());
        verify(api, never()).getInstrumentInfo(any());
        verify(api, never()).getAccountBalance(any());
        verify(api, never()).getPendingOrders(any());
        verify(api, never()).getOrderHistory(any());
        verify(api, never()).getFills(any());
        verify(api, never()).getPositions(any());
    }

    @Test
    void liveRetainsExchangeBalancesAndPrivateInstrumentRules() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        OkxApi api = marketApi();
        var account = new AccountBalanceResp();
        account.setTotalEq("50000");
        when(api.getAccountBalance(any())).thenReturn(OkxResponse.success(List.of(account)));
        when(api.getPendingOrders(any())).thenReturn(OkxResponse.success(List.of()));
        when(api.getOrderHistory(any())).thenReturn(OkxResponse.success(List.of()));
        when(api.getFills(any())).thenReturn(OkxResponse.success(List.of()));
        TradingStateRepository state = mock(TradingStateRepository.class);
        when(state.getState()).thenReturn(new TradingState());
        PaperAccountService paper = mock(PaperAccountService.class);
        var collector = new MarketContextCollector(api, properties, state,
                event -> TradingEventPublishResult.ACCEPTED, paper);

        var context = collector.collect(TradingTrigger.scheduled());

        assertSame(account, context.getAccountBalance());
        verify(api).getInstrumentInfo(any());
        verify(api, never()).getPublicInstrumentInfo(any());
        verifyNoInteractions(paper);
    }

    private static OkxApi marketApi() {
        OkxApi api = mock(OkxApi.class);
        var ticker = new TickerResp();
        ticker.setLast("100");
        when(api.getTicker(any())).thenReturn(OkxResponse.success(List.of(ticker)));
        when(api.getOrderBook(any())).thenReturn(OkxResponse.success(List.of(new OrderBookResp())));
        when(api.getCandles(any())).thenReturn(OkxResponse.success(List.of()));
        when(api.getInstrumentInfo(any())).thenReturn(OkxResponse.success(List.of(new InstrumentInfoResp())));
        when(api.getPublicInstrumentInfo(any())).thenReturn(OkxResponse.success(List.of(new InstrumentInfoResp())));
        return api;
    }
}
