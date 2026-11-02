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
import com.trade.trading.port.TradingStateStore;
import com.trade.trading.domain.model.TradingState;
import com.trade.trading.domain.model.TradingDecisionRecord;
import com.trade.trading.domain.model.TradingAction;
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
    private final TradingStateStore state = mock(TradingStateStore.class);
    private final Map<String, StrategyConfigurationVersion> saved = new HashMap<>();
    private TradingStrategyRegistry registry;
    private TradingAiAdvisor advisor;
    private static final String STRATEGY = "threshold-event-default";

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(state.getState()).thenReturn(new TradingState());
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
        when(configs.withUnchangedVersions(anyMap(), any())).thenAnswer(inv -> {
            Map<String, Long> versions = inv.getArgument(0);
            versions.forEach((id, version) -> {
                if (saved.get(id).version() != version) throw new ConcurrentModificationException();
            });
            return ((Supplier<?>) inv.getArgument(1)).get();
        });
        registry = new TradingStrategyRegistry(List.of(new ThresholdEventStrategy(), new IntradayTechnicalStrategy()), trading, configs);
        when(selection.current()).thenReturn(new ActiveStrategySelection(STRATEGY, 1, null));
        when(selection.activate(anyString(), anyLong())).thenAnswer(inv ->
                new ActiveStrategySelection(inv.getArgument(0), (Long) inv.getArgument(1) + 1, NOW));
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
        advisor = new TradingAiAdvisor(ai, trading, market, registry, selection, paper, reviews, client, notifier, engine, leadership, state, clock);
    }

    @Test void dailyGoalAndVersionBoundPerformanceReachAiAndAuditWithoutDoubleChargingFees() throws Exception {
        Instant now = NOW;
        var day = TradingDay.at(now, ZoneId.of("Asia/Shanghai"));
        var account = PaperAccount.start("session", "BTC-USDT", "USDT", new BigDecimal("1000"), day.start().minusSeconds(86400));
        when(paper.active(anyString())).thenReturn(account);
        when(paper.dailyPerformance(eq("BTC-USDT"), eq("session"), eq(0L), eq(day.start()), any()))
                .thenReturn(new PaperDailyPerformance(12, 6, 6, 6, new BigDecimal("3.25"), new BigDecimal("1.50")));
        var prior = new TradingAiReview("previous", "scope", now.minusSeconds(60), now, "{}",
                proposal("HOLD", STRATEGY, "{}", "0.9", ""), "HELD", "evidence", "NOT_REQUIRED", now, null);
        when(reviews.recent(anyString(), eq(4))).thenReturn(List.of(prior));
        advisor.review();
        var snapshot = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reviews).capture(anyString(), snapshot.capture());
        var tree = new ObjectMapper().readTree(snapshot.getValue());
        var goal = tree.path("dailyObjective");
        assertEquals(day.date().toString(), goal.path("tradingDate").asText());
        assertEquals(java.time.OffsetDateTime.parse("2026-10-28T14:00:00+08:00"),
                java.time.OffsetDateTime.parse(goal.path("observedAtLocal").asText()));
        assertEquals(600, goal.path("remainingMinutes").asLong());
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
        verify(reviews).claim(anyString(), anyString(), any(), eq(now.plusSeconds(1800)), argThat(expiry -> !expiry.isAfter(day.end())));
        // Reaching the frequency target never disables strategy maintenance or risk management.
        verify(reviews).applied(anyString(), contains("\"executionMode\":\"PAPER\""), anyString(), any());
        verify(reviews, never()).complete(anyString(), eq("APPLIED"), anyString(), any());
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
        verifyNoInteractions(state);
    }

    @Test void inactivityEvidenceIsBoundedToCurrentPaperSessionAndTradingDay() throws Exception {
        var day = TradingDay.at(NOW, ZoneId.of("Asia/Shanghai"));
        when(paper.active(anyString())).thenReturn(PaperAccount.start("session", "BTC-USDT", "USDT",
                new BigDecimal("1000"), day.start().plusSeconds(60)));
        List<TradingDecisionRecord> records = new ArrayList<>();
        for (int i = 0; i < 15; i++) records.add(diagnostic(NOW.minusSeconds(i * 60L), "session"));
        records.add(diagnostic(NOW.plusSeconds(1), "session"));
        records.add(diagnostic(day.start().minusSeconds(1), "session"));
        records.add(diagnostic(day.start().plusSeconds(30), "session"));
        records.add(diagnostic(NOW, "previous-session"));
        records.add(diagnostic(NOW, "session").setTimestamp("invalid"));
        records.add(diagnostic(NOW, "session").setMetadata(null));
        records.add(null);
        when(state.getState()).thenReturn(new TradingState().setRecentDecisions(records));
        advisor.review();
        var snapshot = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reviews).capture(anyString(), snapshot.capture());
        var decisions = new ObjectMapper().readTree(snapshot.getValue())
                .path("dailyObjective").path("recentDecisionsNewestFirst");
        assertEquals(12, decisions.size());
        assertEquals(NOW.toString(), decisions.get(0).path("timestamp").asText());
        assertEquals("Entry gate blocked", decisions.get(0).path("skipReason").asText());
        assertEquals(NOW.minusSeconds(660).toString(), decisions.get(11).path("timestamp").asText());
        assertFalse(snapshot.getValue().contains("private-provider-error"));
        assertFalse(snapshot.getValue().contains("private-order-id"));
        verify(client).generateJson(argThat(p -> p.contains("Repeated HOLDs are not fills")
                && p.contains("an empty sample is unknown")));
    }

    @Test void assessmentStatusReadsReservedWindowAndCurrentAppliedVersionWithoutRequestingAi() {
        Instant reserved = NOW.plusSeconds(36000);
        when(reviews.nextReviewAt(anyString())).thenReturn(reserved);
        var status = advisor.assessmentStatus();
        assertEquals(reserved.toString(), status.get("nextReviewAt"));
        assertEquals(STRATEGY, status.get("activeStrategyId"));
        assertEquals(1L, status.get("activeStrategyConfigVersion"));
        verifyNoInteractions(client, market, paper);
        when(reviews.nextReviewAt(anyString())).thenReturn(null);
        assertNull(advisor.assessmentStatus().get("nextReviewAt"));
    }

    @Test void explicitlyConfiguredDailyIntervalStillEndsAtMidnight() {
        ai.setReviewIntervalMs(86400000);
        advisor.review();
        verify(reviews).claim(anyString(), anyString(), eq(NOW),
                eq(TradingDay.at(NOW, ZoneId.of("Asia/Shanghai")).end()), any());
    }

    private TradingDecisionRecord diagnostic(Instant at, String session) {
        return new TradingDecisionRecord().setTimestamp(at.toString()).setStrategyId(STRATEGY)
                .setStrategyConfigVersion(2).setAction(TradingAction.BUY).setExecutionStatus("SKIPPED")
                .setSkipReason("Entry gate blocked").setReason("Signal present")
                .setError("private-provider-error").setOrderId("private-order-id")
                .setMetadata(Map.of("paperSessionId", session, "arbitraryData", "private-provider-error"));
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
        verify(reviews).applied(anyString(), contains("\"fromRevision\":1"), anyString(), any());
        var order = inOrder(reviews, paper);
        order.verify(reviews).beginApplying(anyString(), any());
        order.verify(paper).changeStrategyWhileFlat(eq("BTC-USDT"), eq("session"), eq(0L), any());
        verify(notifier, never()).send(anyString());
        verify(market, never()).collect(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ExecutionMode.class, names = {"PAPER", "LIVE"})
    void canSwitchToExistingStrategyWithoutInventingOrEditingIt(ExecutionMode mode) {
        trading.setExecutionMode(mode);
        var second = new TradingProperties.StrategyInstanceProperties();
        second.setId("second"); second.setType("threshold-event"); second.setBar("1m");
        trading.setStrategies(List.of(trading.getStrategies().getFirst(), second));
        when(client.generateJson(anyString())).thenReturn(proposal("SWITCH", "second", "{}", "0.9", ""));
        advisor.review();
        verify(selection).activate("second", 1L);
        assertEquals(1, registry.configuration("second").version());
        verify(reviews).applied(anyString(), argThat(a -> a.contains("\"fromStrategyId\":\"" + STRATEGY + "\"")
                && a.contains("\"toStrategyId\":\"second\"") && a.contains("\"toRevision\":2")), anyString(), any());
        assertEquals(mode, trading.getExecutionMode());
    }

    @Test void tuneBeyondTwentyPercentPersistsBeforeAfterAndNotifiesWithoutLogicSuggestion() throws Exception {
        when(notifier.enabled()).thenReturn(true);
        when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY,
                "{\"price-move-window-candles\":20}", "0.9", ""));
        advisor.review();
        var audit = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reviews).applied(anyString(), audit.capture(), eq("test evidence"), eq(NOW));
        var data = new ObjectMapper().readTree(audit.getValue());
        assertEquals(5, data.path("fromConfiguration").path("params").path("price-move-window-candles").asInt());
        assertEquals(20, data.path("toConfiguration").path("params").path("price-move-window-candles").asInt());
        assertEquals(2, data.path("toConfiguration").path("version").asLong());
        var ordered = inOrder(reviews, notifier);
        ordered.verify(reviews).applied(anyString(), anyString(), anyString(), any());
        ordered.verify(notifier).send(argThat(message -> message.contains("已生效")
                && message.contains("price-move-window-candles: 5 → 20") && message.contains("PAPER")));
        verify(reviews).notification(anyString(), eq("SENT"));
    }

    @Test void canEditExistingIntradayModeAndExitParametersWithMoreThanSixKeys() {
        var intraday = new TradingProperties.StrategyInstanceProperties();
        intraday.setId(STRATEGY); intraday.setType("intraday-technical"); intraday.setBar("15m");
        trading.setStrategies(List.of(intraday));
        when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY,
                """
                {"mode":"RANGE_REVERSION","ema-fast-period":8,"ema-slow-period":24,
                 "rsi-period":12,"atr-period":12,"stop-loss-percent":0.01,"take-profit-percent":0.03,
                 "min-net-reward-percent":0.004}
                """, "0.9", ""));
        advisor.review();
        var config = registry.configuration(STRATEGY);
        assertEquals(2, config.version());
        assertEquals("RANGE_REVERSION", config.params().get("mode"));
        assertEquals(new BigDecimal("0.01"), config.params().get("stop-loss-percent"));
        assertEquals(ExecutionMode.PAPER, trading.getExecutionMode());
    }

    @Test void executionModeAndOtherExecutionSettingsCannotBeEditedByModel() {
        for (String params : List.of("{\"execution-mode\":\"LIVE\"}", "{\"mode\":\"LIVE\"}",
                "{\"live-trading-enabled\":true}", "{\"estimated-slippage-rate\":0}",
                "{\"require-confirmed-candle\":false}", "{\"max-daily-loss-ratio\":1}")) {
            when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY, params, "0.9", ""));
            assertThrows(IllegalStateException.class, advisor::review);
        }
        assertEquals(1, registry.configuration(STRATEGY).version());
        assertEquals(ExecutionMode.PAPER, trading.getExecutionMode());
        verifyNoInteractions(engine);
    }

    @Test void modeChangeDuringAssessmentAndLiveExposurePreventApplication() {
        when(client.generateJson(anyString())).thenAnswer(inv -> {
            trading.setExecutionMode(ExecutionMode.LIVE);
            return proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", "");
        });
        advisor.review();
        verify(reviews).complete(anyString(), eq("CONFLICT"), anyString(), any());
        doThrow(new ConcurrentModificationException("Pending live order")).when(engine).withStrategyMaintenance(any());
        advisor.review();
        verify(reviews, times(2)).complete(anyString(), eq("CONFLICT"), anyString(), any());
        verify(reviews, never()).applied(anyString(), anyString(), anyString(), any());
        assertEquals(1, registry.configuration(STRATEGY).version());
    }

    @Test void committedPendingNotificationIsSentBeforeNextAssessmentWithoutReapplying() throws Exception {
        var before = registry.configuration(STRATEGY);
        var application = new TradingAiApplication("PAPER", "BTC-USDT", NOW.toString(), STRATEGY, 1, before,
                STRATEGY, 1, before);
        when(reviews.pendingNotifications(anyString())).thenReturn(List.of(new TradingAiReview("committed", "scope", NOW, NOW,
                "{}", proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":6}", "0.9", ""),
                "APPLIED", "test evidence", "PENDING", NOW, new ObjectMapper().writeValueAsString(application))));
        when(reviews.claim(anyString(), anyString(), any(), any(), any())).thenReturn(false);
        when(notifier.enabled()).thenReturn(true);
        advisor.review();
        verify(notifier).send(contains("committed"));
        verify(reviews).notification("committed", "SENT");
        verifyNoInteractions(engine, client);
        assertEquals(1, registry.configuration(STRATEGY).version());
    }

    @Test void notificationFailureKeepsAppliedOutcomeAndDoesNotReplayChange() {
        when(notifier.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("secret-token")).when(notifier).send(anyString());
        assertThrows(IllegalStateException.class, advisor::review);
        assertEquals(2, registry.configuration(STRATEGY).version());
        verify(reviews).applied(anyString(), anyString(), anyString(), any());
        verify(reviews).notification(anyString(), eq("UNKNOWN"));
        verify(reviews, never()).complete(anyString(), anyString(), anyString(), any());
    }

    @Test void disabledAndDurableCooldownDoNotCallAi() {
        ai.setEnabled(false); advisor.review();
        verifyNoInteractions(client, market, reviews);
        ai.setEnabled(true);
        when(reviews.claim(anyString(), anyString(), any(), any(), any())).thenReturn(false);
        advisor.review();
        verifyNoInteractions(client, market);
    }

    @Test void liveAutoAppliesButAdvisoryModeDoesNot() {
        trading.setExecutionMode(ExecutionMode.LIVE); advisor.review();
        verify(reviews).applied(anyString(), contains("\"executionMode\":\"LIVE\""), anyString(), any());
        verify(paper, never()).active(anyString());
        verify(paper, never()).changeStrategyWhileFlat(anyString(), anyString(), anyLong(), any());
        assertEquals(2, registry.configuration(STRATEGY).version());
        assertEquals(ExecutionMode.LIVE, trading.getExecutionMode());
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
                proposal("TUNE", STRATEGY, "{\"price-move-window-candles\":2000}", "0.9", ""),
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

    @Test void blockedLiveChangeStillNotifiesValidNewModeSuggestion() {
        trading.setExecutionMode(ExecutionMode.LIVE);
        when(notifier.enabled()).thenReturn(true);
        when(client.generateJson(anyString())).thenReturn(proposal("TUNE", STRATEGY,
                "{\"price-move-window-candles\":6}", "0.9", "建议新增波动率收缩模式并回测"));
        doThrow(new ConcurrentModificationException("Exposure")).when(engine).withStrategyMaintenance(any());
        advisor.review();
        verify(reviews).complete(anyString(), eq("CONFLICT"), anyString(), any());
        verify(notifier).send(argThat(message -> message.contains("建议新增波动率收缩模式并回测")
                && message.contains("未实施") && message.contains("CONFLICT")));
        assertEquals(1, registry.configuration(STRATEGY).version());
    }

    @Test void longCombinedChangeAndSuggestionMessageStaysWithinTelegramLimit() {
        String longId = "s".repeat(128);
        var intraday = new TradingProperties.StrategyInstanceProperties();
        intraday.setId(longId); intraday.setType("intraday-technical"); intraday.setBar("15m");
        trading.setStrategies(List.of(intraday));
        when(selection.current()).thenReturn(new ActiveStrategySelection(longId, 1, null));
        var config = registry.configuration(longId);
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        config.params().forEach((key, value) -> {
            if (TradingAiPolicy.TUNABLE.contains(key)) params.put(key, value);
        });
        params.put("ema-fast-period", 8);
        params.put("mode", "RANGE_REVERSION");
        when(client.generateJson(anyString())).thenReturn(writeProposal(longId, params, "证".repeat(1000), "议".repeat(1800)));
        when(notifier.enabled()).thenReturn(true);
        advisor.review();
        verify(notifier).send(argThat(message -> message.length() <= 4096
                && message.contains("已生效") && message.contains("完整内容见数据库评估")));
    }

    private String writeProposal(String strategyId, Map<String, Object> params, String reason, String suggestion) {
        try {
            return new ObjectMapper().writeValueAsString(new TradingAiProposal(TradingAiProposal.Action.TUNE,
                    strategyId, params, new BigDecimal("0.9"), "行情".repeat(50), reason, suggestion));
        } catch (Exception e) { throw new AssertionError(e); }
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
