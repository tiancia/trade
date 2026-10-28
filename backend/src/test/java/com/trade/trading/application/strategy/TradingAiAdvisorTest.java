package com.trade.trading.application.strategy;

import com.trade.client.ai.AiTextClient;
import com.trade.client.okx.dto.CandleResp;
import com.trade.trading.port.*;
import com.trade.trading.application.runtime.TradingLeadershipService;
import com.trade.trading.domain.model.*;
import com.trade.trading.domain.paper.PaperAccount;
import com.trade.trading.domain.paper.PaperDailyPerformance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.trading.infrastructure.config.TradingAiProperties;
import com.trade.trading.infrastructure.config.TradingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradingAiAdvisorTest {
    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final TradingAiProperties ai = new TradingAiProperties();
    private final TradingProperties trading = new TradingProperties();
    private final TradingMarketSource market = mock(TradingMarketSource.class);
    private final PaperAccountStore paper = mock(PaperAccountStore.class);
    private final TradingAiReviewStore reviews = mock(TradingAiReviewStore.class);
    private final AiTextClient client = mock(AiTextClient.class);
    private final TradingAiNotifier notifier = mock(TradingAiNotifier.class);
    private final TradingStrategySelectionService selection = mock(TradingStrategySelectionService.class);
    private final TradingStrategyEngine engine = mock(TradingStrategyEngine.class);
    private final TradingLeadershipService leadership = mock(TradingLeadershipService.class);
    private final Map<String, StrategyConfigurationVersion> saved = new HashMap<>();
    private TradingStrategyRegistry registry;
    private TradingAiAdvisor advisor;
    private static final String STRATEGY = "threshold-event-default";

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        ai.setEnabled(true); ai.setAutoApply(true); ai.setCandleCount(32);
        trading.getLeadership().setEnabled(false);
        StrategyConfigurationStore configs = mock(StrategyConfigurationStore.class);
        when(configs.currentOrCreate(any())).thenAnswer(inv -> {
            StrategyConfigurationVersion baseline = inv.getArgument(0);
            return saved.computeIfAbsent(baseline.strategyId(), ignored -> baseline);
        });
        when(configs.update(anyLong(), any())).thenAnswer(inv -> {
            long expected = inv.getArgument(0);
            StrategyConfigurationVersion next = inv.getArgument(1);
            if (saved.get(next.strategyId()).version() != expected) throw new ConcurrentModificationException();
            saved.put(next.strategyId(), next); return next;
        });
        when(configs.withUnchangedVersions(anyMap(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        registry = new TradingStrategyRegistry(List.of(new ThresholdEventStrategy()), trading, configs);
        when(selection.current()).thenReturn(new ActiveStrategySelection(STRATEGY, 1, null));
        when(market.getFifteenMinuteCandles()).thenReturn(candles(NOW, 32));
        when(paper.active(anyString())).thenReturn(PaperAccount.start("session", "BTC-USDT", "USDT", new BigDecimal("1000"), NOW));
        when(paper.dailyPerformance(anyString(), anyString(), anyLong(), any(), any()))
                .thenReturn(new PaperDailyPerformance(0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        when(paper.changeStrategyWhileFlat(anyString(), anyString(), anyLong(), any())).thenAnswer(inv -> ((java.util.function.BooleanSupplier) inv.getArgument(3)).getAsBoolean());
        when(engine.withStrategyMaintenance(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(reviews.claim(anyString(), anyString(), any(), any(), any())).thenReturn(true);
        when(reviews.beginApplying(anyString(), any())).thenReturn(true);
        when(reviews.claimNotification(anyString(), any(), any())).thenReturn(true);
        when(leadership.runIfLeader(anyString(), any())).thenAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return true; });
        when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", ""));
        advisor = new TradingAiAdvisor(ai, trading, market, registry, selection, paper, reviews, client, notifier, engine, leadership, clock);
    }

    @Test void dailyGoalAndVersionBoundPerformanceReachAiAndAuditWithoutDoubleChargingFees() throws Exception {
        Instant now = NOW;
        var day = TradingDay.at(now, ZoneId.of("Asia/Shanghai"));
        var account = PaperAccount.start("session", "BTC-USDT", "USDT", new BigDecimal("1000"), day.start().minusSeconds(86400));
        when(paper.active(anyString())).thenReturn(account);
        when(paper.dailyPerformance(eq("BTC-USDT"), eq("session"), eq(0L), eq(day.start()), any()))
                .thenReturn(new PaperDailyPerformance(12, 6, 6, 6, new BigDecimal("3.25"), new BigDecimal("1.50")));
        var prior = new TradingAiReview("previous", "scope", now.minusSeconds(60), now, "{}",
                proposal("HOLD", STRATEGY, "{}", "0.9", ""), "HELD", "evidence", "NOT_REQUIRED", now);
        when(reviews.recent(anyString(), eq(4))).thenReturn(List.of(prior));
        advisor.review();
        var snapshot = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reviews).capture(anyString(), snapshot.capture());
        var tree = new ObjectMapper().readTree(snapshot.getValue());
        var goal = tree.path("dailyObjective");
        assertEquals(day.date().toString(), goal.path("tradingDate").asText());
        assertEquals(5, goal.path("targetMinClosedTrades").asInt());
        assertEquals(8, goal.path("targetMaxClosedTrades").asInt());
        assertEquals("ROUND_TRIP_CLOSED", goal.path("countUnit").asText());
        assertEquals("SOFT", goal.path("targetPolicy").asText());
        assertEquals(6, goal.path("today").path("metrics").path("closedTrades").asInt());
        assertEquals("3.25", goal.path("today").path("metrics").path("realizedNetPnl").asText());
        assertEquals("FROM_DAY_START", goal.path("previousDay").path("sessionCoverage").asText());
        assertEquals("previous", tree.path("previousReviewsNewestFirst").get(0).path("reviewId").asText());
        assertEquals(0, trading.getTakerFeeRate().compareTo(tree.path("executionConstraints").path("takerFeeRate").decimalValue()));
        verify(client).generateJson(argThat(p -> p.contains("never force entries") && p.contains("do not subtract feesQuote")));
        verify(reviews).claim(anyString(), anyString(), any(), eq(day.end()), argThat(expiry -> !expiry.isAfter(day.end())));
        // Reaching the frequency target never disables strategy maintenance or risk management.
        verify(reviews).complete(anyString(), eq("APPLIED"), anyString(), any());
    }

    @Test void missingSessionOrLivePerformanceIsUnknownRatherThanFabricatedZeroTrades() {
        when(paper.active(anyString())).thenReturn(null);
        trading.getRisk().setDailyZone(" "); // Same fallback as the existing risk-day calculation.
        var daily = advisor.dailyStatus();
        assertEquals("Asia/Shanghai", daily.get("zone"));
        assertEquals(false, daily.get("performanceAvailable"));
        assertFalse(daily.containsKey("today"));
        trading.setExecutionMode(ExecutionMode.LIVE);
        clearInvocations(paper);
        assertEquals(false, advisor.dailyStatus().get("performanceAvailable"));
        verifyNoInteractions(paper);
    }

    @Test void newSessionDoesNotPretendToHaveYesterdaysHistory() {
        when(paper.active(anyString())).thenReturn(PaperAccount.start("session", "BTC-USDT", "USDT",
                new BigDecimal("1000"), NOW.minusSeconds(60)));
        var daily = advisor.dailyStatus();
        assertEquals("NO_SESSION_DATA", ((Map<?, ?>) daily.get("previousDay")).get("sessionCoverage"));
        assertEquals("PARTIAL_DAY", ((Map<?, ?>) daily.get("today")).get("sessionCoverage"));
    }

    @Test void reviewCrossingMidnightCannotApplyOrNotifyOnTheNewDay() {
        Instant justBeforeMidnight = Instant.parse("2026-10-28T15:59:50Z");
        Instant midnight = justBeforeMidnight.plusSeconds(10);
        when(clock.instant()).thenReturn(justBeforeMidnight);
        when(market.getFifteenMinuteCandles()).thenReturn(candles(justBeforeMidnight, 32));
        when(client.generateJson(anyString())).thenAnswer(inv -> {
            when(clock.instant()).thenReturn(midnight);
            return proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", "day-specific advice");
        });
        advisor.review();
        verify(reviews).claim(anyString(), anyString(), eq(justBeforeMidnight), eq(midnight), eq(midnight));
        verify(reviews).complete(anyString(), eq("STALE"), anyString(), eq(midnight));
        verifyNoInteractions(engine, notifier);
        assertEquals(1, registry.configuration(STRATEGY).version());
    }

    @Test void validTuneUsesVersionHistoryAndDurableIntentWithoutPlacingOrders() {
        advisor.review();
        assertEquals(2, registry.configuration(STRATEGY).version());
        assertEquals(6, registry.configuration(STRATEGY).params().get("price-move-window-candles"));
        assertTrue(registry.configuration(STRATEGY).reason().startsWith("AI "));
        verify(reviews).complete(anyString(), eq("APPLIED"), anyString(), any());
        var order = inOrder(reviews, paper);
        order.verify(reviews).beginApplying(anyString(), any());
        order.verify(paper).changeStrategyWhileFlat(eq("BTC-USDT"), eq("session"), eq(0L), any());
        verify(notifier, never()).send(anyString());
        verify(market, never()).collect(any());
    }

    @Test void canSwitchToExistingStrategyWithoutInventingOrEditingIt() {
        var second = new TradingProperties.StrategyInstanceProperties();
        second.setId("second"); second.setType("threshold-event"); second.setBar("1m");
        trading.setStrategies(List.of(trading.getStrategies().getFirst(), second));
        when(client.generateJson(anyString())).thenReturn(proposal("SWITCH", "second", "{}", "0.9", ""));
        advisor.review();
        verify(selection).activate("second", 1L);
        assertEquals(1, registry.configuration("second").version());
    }

    @Test void disabledAndDurableCooldownDoNotCallAi() {
        ai.setEnabled(false); advisor.review();
        verifyNoInteractions(client, market, reviews);
        ai.setEnabled(true);
        when(reviews.claim(anyString(), anyString(), any(), any(), any())).thenReturn(false);
        advisor.review();
        verifyNoInteractions(client, market);
    }

    @Test void liveAndAdvisoryModeNeverApply() {
        trading.setExecutionMode(ExecutionMode.LIVE); advisor.review();
        verify(reviews).complete(anyString(), eq("RECOMMENDED"), anyString(), any());
        verify(paper, never()).active(anyString());
        verifyNoInteractions(engine);
        assertEquals(1, registry.configuration(STRATEGY).version());
        clearInvocations(reviews);
        trading.setExecutionMode(ExecutionMode.PAPER); ai.setAutoApply(false); advisor.review();
        verify(reviews).complete(anyString(), eq("RECOMMENDED"), anyString(), any());
    }

    @Test void lowConfidenceAndOpenPositionKeepOriginalConfiguration() {
        when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.6", ""));
        advisor.review();
        verify(reviews).complete(anyString(), eq("LOW_CONFIDENCE"), anyString(), any());
        when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", ""));
        var open = PaperAccount.start("session", "BTC-USDT", "USDT", new BigDecimal("1000"), NOW)
                .fill("BUY", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, NOW).account();
        when(paper.active(anyString())).thenReturn(open);
        advisor.review();
        verify(reviews).complete(anyString(), eq("POSITION_NOT_FLAT"), anyString(), any());
        assertEquals(1, registry.configuration(STRATEGY).version());
        verifyNoInteractions(engine);
    }

    @Test void manualChangeDuringAiRequestWins() {
        when(client.generateJson(anyString())).thenAnswer(inv -> {
            when(selection.current()).thenReturn(new ActiveStrategySelection("another", 2, null));
            return proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", "");
        });
        advisor.review();
        verify(reviews).complete(anyString(), eq("CONFLICT"), anyString(), any());
        assertEquals(1, registry.configuration(STRATEGY).version());
    }

    @Test void configRevisionAndSimulationConflictCannotOverwriteState() {
        when(client.generateJson(anyString())).thenAnswer(inv -> {
            var old = registry.configuration(STRATEGY);
            registry.updateConfiguration(STRATEGY, old.version(), "manual", old.params());
            return proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", "");
        });
        advisor.review();
        assertEquals("manual", registry.configuration(STRATEGY).reason());
        verify(reviews).complete(anyString(), eq("CONFLICT"), anyString(), any());
        when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", ""));
        doThrow(new ConcurrentModificationException()).when(paper).changeStrategyWhileFlat(anyString(), anyString(), anyLong(), any());
        advisor.review();
        verify(reviews, times(2)).complete(anyString(), eq("CONFLICT"), anyString(), any());
    }

    @Test void stoppedTaskAndExpiredDurableClaimDoNotApply() {
        AtomicBoolean active = new AtomicBoolean(true);
        when(client.generateJson(anyString())).thenAnswer(inv -> {
            active.set(false);
            return proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", "add tests");
        });
        advisor.review(active::get);
        verify(reviews).complete(anyString(), eq("CANCELLED"), anyString(), any());
        verify(notifier, never()).send(anyString());
        when(reviews.beginApplying(anyString(), any())).thenReturn(false);
        when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", ""));
        advisor.review();
        verify(reviews).complete(anyString(), eq("STALE"), anyString(), any());
        assertEquals(1, registry.configuration(STRATEGY).version());
    }

    @Test void unsafeParametersUnknownStrategiesAndMalformedJsonAreRejected() {
        for (String bad : List.of("not json", proposal("SWITCH", "invented", "{}", "0.9", ""),
                proposal("TUNE", STRATEGY, "{\"buy-quote-amount\":100}", "0.9", ""),
                proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":20}", "0.9", ""),
                proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":5.5}", "0.9", ""))) {
            when(client.generateJson(anyString())).thenReturn(bad);
            assertThrows(IllegalStateException.class, advisor::review);
        }
        assertEquals(1, registry.configuration(STRATEGY).version());
        verify(reviews, times(5)).complete(anyString(), eq("REJECTED"), anyString(), any());
    }

    @Test void malformedOrStaleMarketNeverCallsAi() {
        when(market.getFifteenMinuteCandles()).thenReturn(candles(NOW.minusSeconds(3600), 32));
        assertThrows(IllegalStateException.class, advisor::review);
        verifyNoInteractions(client);
        var broken = candles(NOW, 33); broken.remove(10);
        assertThrows(IllegalArgumentException.class, () -> TradingAiAdvisor.confirmedCandles(broken, 32, NOW));
    }

    @Test void logicSuggestionUsesOneShotIndependentNotification() {
        when(client.generateJson(anyString())).thenReturn(proposal("HOLD", STRATEGY, "{}", "0.9", "建议补充趋势失效后的退出验证"));
        when(notifier.enabled()).thenReturn(true);
        advisor.review();
        verify(notifier).send(contains("建议补充趋势失效后的退出验证"));
        verify(reviews).notification(anyString(), eq("SENT"));
        when(reviews.claimNotification(anyString(), any(), any())).thenReturn(false);
        advisor.review();
        verify(notifier, times(1)).send(anyString());
    }

    @Test void providerAndTelegramFailuresAreAuditedWithoutLeakingSecretsOrReplaying() {
        when(client.generateJson(anyString())).thenThrow(new IllegalStateException("secret-token"));
        var failure = assertThrows(IllegalStateException.class, advisor::review);
        assertFalse(failure.toString().contains("secret-token")); assertNull(failure.getCause());
        verify(reviews).complete(anyString(), eq("FAILED"), argThat(detail -> !detail.contains("secret-token")), any());
        doReturn(proposal("HOLD", STRATEGY, "{}", "0.9", "logic advice")).when(client).generateJson(anyString());
        when(notifier.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("secret-token")).when(notifier).send(anyString());
        assertThrows(IllegalStateException.class, advisor::review);
        verify(reviews).notification(anyString(), eq("UNKNOWN"));
        verify(notifier, times(1)).send(anyString());
    }

    static List<CandleResp> candles(Instant now, int count) {
        long end = now.toEpochMilli() / 900000 * 900000;
        List<CandleResp> result = new ArrayList<>();
        for (int i = count; i > 0; i--) result.add(new CandleResp(List.of(
                String.valueOf(end - i * 900000L), "100", "101", "99", "100.5", "10", "10", "1005", "1")));
        return result;
    }
    static String proposal(String action, String id, String params, String confidence, String logic) {
        return "{\"action\":\"" + action + "\",\"strategyId\":\"" + id + "\",\"parameters\":" + params
                + ",\"confidence\":" + confidence + ",\"marketRegime\":\"range\",\"reason\":\"test evidence\",\"logicSuggestion\":\"" + logic + "\"}";
    }
}
