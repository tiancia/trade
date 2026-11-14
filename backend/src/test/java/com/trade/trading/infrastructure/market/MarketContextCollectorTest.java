package com.trade.trading.infrastructure.market;

import com.trade.client.okx.OkxApi;
import com.trade.client.okx.dto.*;
import com.trade.trading.application.event.TradingEventPublishResult;
import com.trade.trading.application.event.CandleBatchPayload;
import com.trade.trading.application.event.TradingEvent;
import com.trade.trading.application.event.TradingEventSource;
import com.trade.trading.application.paper.PaperAccountService;
import com.trade.trading.domain.model.ExecutionMode;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.model.TradingTrigger;
import com.trade.trading.infrastructure.config.TradingProperties;
import com.trade.trading.infrastructure.persistence.TradingStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketContextCollectorTest {
    @Test
    void deterministicExitContextNeverCallsUnrelatedIntervalsBookOrPrivateHistory() {
        TradingProperties properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        OkxApi api = marketApi();
        when(api.getOrderHistory(any())).thenThrow(new IllegalStateException("history down"));
        when(api.getFills(any())).thenThrow(new IllegalStateException("fills down"));
        when(api.getOrderBook(any())).thenThrow(new IllegalStateException("book down"));
        when(api.getCandles(any())).thenAnswer(call -> {
            CandlesReq request = call.getArgument(0);
            if (!"15m".equals(request.getBar())) throw new IllegalStateException("unrelated candle service down");
            return OkxResponse.success(List.of(candle("900000")));
        });
        when(api.getAccountBalance(any())).thenReturn(OkxResponse.success(List.of(account(List.of()))));
        TradingStateRepository state = mock(TradingStateRepository.class);
        when(state.getState()).thenReturn(new TradingState());
        var collector = new MarketContextCollector(api, properties, state,
                event -> TradingEventPublishResult.ACCEPTED, mock(PaperAccountService.class));

        var context = collector.collect(TradingTrigger.scheduled(), "15m");

        assertEquals(1, context.getFifteenMinuteCandles().size());
        assertTrue(context.getOneMinuteCandles().isEmpty());
        verify(api, never()).getOrderHistory(any());
        verify(api, never()).getFills(any());
        verify(api, never()).getOrderBook(any());
        verify(api, never()).getPendingOrders(any());
        when(api.getAccountBalance(any())).thenThrow(new IllegalStateException("required account data down"));
        assertThrows(IllegalStateException.class, () -> collector.collect(TradingTrigger.scheduled(), "15m"));
    }
    @Test
    void fifteenMinuteFallbackPublishesToTheSharedPipelineWithoutPrivateAccountAccess() {
        TradingProperties properties = new TradingProperties();
        OkxApi api = marketApi();
        List<CandleResp> history = List.of(candle("900000"));
        when(api.getCandles(any())).thenReturn(OkxResponse.success(history));
        List<TradingEvent> events = new ArrayList<>();
        var collector = new MarketContextCollector(api, properties, mock(TradingStateRepository.class), event -> {
            events.add(event);
            return TradingEventPublishResult.ACCEPTED;
        }, mock(PaperAccountService.class));

        assertEquals(history, collector.getFifteenMinuteCandles());

        verify(api).getCandles(argThat(request -> "15m".equals(request.getBar())
                && "200".equals(request.getLimit())));
        assertEquals(1, events.size());
        assertEquals(TradingEventSource.OKX_REST_EVENT_FALLBACK, events.getFirst().source());
        assertEquals("15m", ((CandleBatchPayload) events.getFirst().payload()).bar());
        assertEquals(history, ((CandleBatchPayload) events.getFirst().payload()).candles());
        verify(api, never()).getAccountBalance(any());
    }

    @Test
    void collectsFifteenMinuteHistoryAlongsideLegacyBarsAndPublishesItForAuditAndPersistence() {
        TradingProperties properties = new TradingProperties();
        properties.setFifteenMinuteCandleLimit(150);
        OkxApi api = marketApi();
        Map<String, List<CandleResp>> bars = Map.of(
                "1m", List.of(candle("60000")),
                "5m", List.of(candle("300000")),
                "15m", List.of(candle("900000"))
        );
        when(api.getCandles(any())).thenAnswer(invocation -> {
            CandlesReq request = invocation.getArgument(0);
            return OkxResponse.success(bars.get(request.getBar()));
        });
        TradingStateRepository state = mock(TradingStateRepository.class);
        when(state.getState()).thenReturn(new TradingState());
        List<TradingEvent> events = new ArrayList<>();
        var collector = new MarketContextCollector(api, properties, state, event -> {
            events.add(event);
            return TradingEventPublishResult.ACCEPTED;
        }, mock(PaperAccountService.class));

        var context = collector.collect(TradingTrigger.scheduled());

        assertEquals(bars.get("1m"), context.getOneMinuteCandles());
        assertEquals(bars.get("5m"), context.getFiveMinuteCandles());
        assertEquals(bars.get("15m"), context.getFifteenMinuteCandles());
        assertEquals(bars.get("15m"), context.getAiParameters().get("candles15mNewestFirst"));
        assertTrue(context.getAiParametersJson().contains("candles15mNewestFirst"));
        verify(api).getCandles(argThat(request -> "15m".equals(request.getBar())
                && "150".equals(request.getLimit()) && "BTC-USDT".equals(request.getInstId())));
        assertEquals(4, events.size());
        assertEquals(List.of("1m", "5m", "15m"), events.stream()
                .filter(event -> event.payload() instanceof CandleBatchPayload)
                .map(event -> ((CandleBatchPayload) event.payload()).bar()).toList());
        assertTrue(events.stream().allMatch(event -> event.source() == TradingEventSource.OKX_REST_DECISION
                && events.getFirst().correlationId().equals(event.correlationId())));
        verify(api, never()).getAccountBalance(any());
    }

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
        var cash = balance("usdt", "400");
        var account = account(List.of(cash));
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
        assertSame(cash, context.getQuoteBalance());
        assertNull(context.getBaseBalance());
        verify(api).getInstrumentInfo(any());
        verify(api, never()).getPublicInstrumentInfo(any());
        verifyNoInteractions(paper);
    }

    @Test
    void liveDecisionRejectsAmbiguousOrIncompleteAccountData() {
        var properties = new TradingProperties();
        properties.setExecutionMode(ExecutionMode.LIVE);
        OkxApi api = marketApi();
        var collector = new MarketContextCollector(api, properties, mock(TradingStateRepository.class),
                event -> TradingEventPublishResult.ACCEPTED, mock(PaperAccountService.class));
        var funded = account(List.of(balance("USDT", "1000")));
        for (var accounts : List.of(List.of(funded, account(List.of())), List.of(new AccountBalanceResp()),
                List.of(account(List.of(balance("USDT", "1000"), balance("usdt", "0")))),
                List.of(account(List.of(balance("BTC", "2"), balance("btc", "0")))),
                List.of(account(List.of(balance(null, "1000")))))) {
            when(api.getAccountBalance(any())).thenReturn(OkxResponse.success(accounts));
            assertThrows(IllegalStateException.class, () -> collector.collect(TradingTrigger.scheduled(), "15m"));
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "0", "-1"})
    void invalidTickerPriceCannotReachDecisionOrMarketEvents(String price) {
        var api = marketApi();
        when(api.getTicker(any())).thenReturn(OkxResponse.success(List.of(ticker("BTC-USDT", price))));
        List<TradingEvent> events = new ArrayList<>();
        var collector = new MarketContextCollector(api, new TradingProperties(), mock(TradingStateRepository.class), event -> {
            events.add(event);
            return TradingEventPublishResult.ACCEPTED;
        }, mock(PaperAccountService.class));
        assertThrows(IllegalStateException.class, () -> collector.collect(TradingTrigger.scheduled(), "15m"));
        assertThrows(IllegalStateException.class, collector::getTicker);
        assertTrue(events.isEmpty());
    }

    @Test
    void tickerMustUniquelyIdentifyTheRequestedInstrument() {
        var api = marketApi();
        var collector = new MarketContextCollector(api, new TradingProperties(), mock(TradingStateRepository.class),
                event -> TradingEventPublishResult.ACCEPTED, mock(PaperAccountService.class));
        var expected = ticker("BTC-USDT", "100");
        for (var rows : List.of(List.of(expected, expected), List.of(ticker(null, "100")),
                List.of(ticker("ETH-USDT", "100")))) {
            when(api.getTicker(any())).thenReturn(OkxResponse.success(rows));
            assertThrows(IllegalStateException.class, collector::getTicker);
        }
    }

    @Test
    void mismatchedOrDuplicateInstrumentRulesCannotPopulateTheCache() {
        var api = marketApi();
        var collector = new MarketContextCollector(api, new TradingProperties(), mock(TradingStateRepository.class),
                event -> TradingEventPublishResult.ACCEPTED, mock(PaperAccountService.class));
        var valid = instrument("BTC-USDT");
        var wrongType = instrument("BTC-USDT");
        wrongType.setInstType("MARGIN");
        for (var rows : List.of(List.of(valid, valid), List.of(instrument("ETH-USDT")), List.of(wrongType))) {
            when(api.getPublicInstrumentInfo(any())).thenReturn(OkxResponse.success(rows));
            assertThrows(IllegalStateException.class, () -> collector.collect(TradingTrigger.scheduled(), "15m"));
        }
        when(api.getPublicInstrumentInfo(any())).thenReturn(OkxResponse.success(List.of(valid)));
        assertSame(valid, collector.collect(TradingTrigger.scheduled(), "15m").getInstrument());
    }

    private static AccountBalanceResp account(List<BalanceDetail> balances) {
        var account = new AccountBalanceResp();
        account.setDetails(balances);
        return account;
    }

    private static BalanceDetail balance(String currency, String available) {
        var detail = new BalanceDetail();
        detail.setCcy(currency);
        detail.setAvailBal(available);
        return detail;
    }

    private static TickerResp ticker(String instrument, String price) {
        var ticker = new TickerResp();
        ticker.setInstId(instrument);
        ticker.setLast(price);
        return ticker;
    }

    private static InstrumentInfoResp instrument(String id) {
        var instrument = new InstrumentInfoResp();
        instrument.setInstId(id);
        instrument.setInstType("SPOT");
        return instrument;
    }

    private static OkxApi marketApi() {
        OkxApi api = mock(OkxApi.class);
        when(api.getTicker(any())).thenReturn(OkxResponse.success(List.of(ticker("BTC-USDT", "100"))));
        when(api.getOrderBook(any())).thenReturn(OkxResponse.success(List.of(new OrderBookResp())));
        when(api.getCandles(any())).thenReturn(OkxResponse.success(List.of()));
        when(api.getInstrumentInfo(any())).thenReturn(OkxResponse.success(List.of(instrument("BTC-USDT"))));
        when(api.getPublicInstrumentInfo(any())).thenReturn(OkxResponse.success(List.of(instrument("BTC-USDT"))));
        return api;
    }

    private static CandleResp candle(String timestamp) {
        CandleResp candle = new CandleResp();
        candle.setTs(timestamp);
        candle.setClose("100");
        candle.setConfirm("1");
        return candle;
    }
}
