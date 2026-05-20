package com.trade.textgame;

import com.trade.client.ai.AiTextClient;
import com.trade.textgame.model.CreateTextGameSessionRequest;
import com.trade.textgame.model.SubmitTextGameChoiceRequest;
import com.trade.textgame.model.SubmitTextGameInterludeActionRequest;
import com.trade.textgame.model.TextGameActionDefinition;
import com.trade.textgame.model.TextGameModeDefinition;
import com.trade.textgame.model.TextGameSessionResponse;
import com.trade.textgame.model.TextGameStageDefinition;
import com.trade.textgame.model.TextGameThemeDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextGameServiceTest {
    @Test
    void createsSessionWithFakeAiClient() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse());
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);

        TextGameSessionResponse response = service.createSession(request());

        assertNotNull(response.sessionId());
        assertEquals("life_100_days", response.themeId());
        assertEquals("decision", response.phase());
        assertEquals(0, response.turn());
        assertEquals(0, response.day());
        assertFalse(response.completed());
        assertEquals("Opening", response.scene().title());
        assertEquals("none", response.resolution().status());
        assertEquals(1, aiTextClient.calls());
        assertEquals(0, executor.pendingTasks());
    }

    @Test
    void submitChoiceSchedulesResolutionWithoutCallingAiInline() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse(), turnResponse(1));
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse created = service.createSession(request());

        TextGameSessionResponse response = service.submitChoice(
                created.sessionId().toString(),
                new SubmitTextGameChoiceRequest("A", created.turn())
        );

        assertEquals(1, aiTextClient.calls());
        assertEquals(1, executor.pendingTasks());
        assertEquals("interlude", response.phase());
        assertEquals("pending", response.resolution().status());
        assertFalse(response.resolution().canAdvance());
        assertEquals(1, response.resolution().turn());
        assertEquals(0, response.turn());
        assertEquals(1, response.day());
        assertEquals(0, response.interlude().completedSteps());
        assertEquals(4, response.interlude().totalSteps());
        assertEquals(2, response.interlude().actions().size());
    }

    @Test
    void interludeActionCandidatesRotateAndAvoidImmediateRepeatWithFiveActions() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse(), turnResponse(1));
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor, fiveActionTheme());
        TextGameSessionResponse created = service.createSession(request());

        TextGameSessionResponse pending = submitChoice(service, created);

        assertEquals(List.of("b", "c", "d"), actionIds(pending));

        TextGameSessionResponse afterAction = submitInterlude(service, pending, "d");

        assertEquals(List.of("c", "e", "a"), actionIds(afterAction));
        assertFalse(actionIds(afterAction).contains("d"));
        assertEquals(3, afterAction.interlude().actions().size());
    }

    @Test
    void interludeActionCandidatesPreferDiverseImpactsAndCoolRecentActions() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse(), turnResponse(1));
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor, mixedActionTheme());
        TextGameSessionResponse created = service.createSession(request());

        TextGameSessionResponse pending = submitChoice(service, created);

        assertEquals(List.of("cash_a", "social", "rest"), actionIds(pending));

        TextGameSessionResponse afterAction = submitInterlude(service, pending, "cash_a");

        assertFalse(actionIds(afterAction).contains("cash_a"));
        assertEquals(3, afterAction.interlude().actions().size());
    }

    @Test
    void interludeActionUpdatesStatsWithoutAdvancingMainTurn() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse(), turnResponse(1));
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse created = service.createSession(request());
        TextGameSessionResponse pending = submitChoice(service, created);

        TextGameSessionResponse response = service.submitInterludeAction(
                pending.sessionId().toString(),
                new SubmitTextGameInterludeActionRequest("work", pending.resolution().turn(), pending.interlude().nextStep())
        );

        assertEquals("interlude", response.phase());
        assertEquals(0, response.turn());
        assertEquals(-90, response.stats().get("money"));
        assertEquals(49, response.stats().get("health"));
        assertEquals(31, response.stats().get("risk"));
        assertEquals(1, response.interlude().completedSteps());
        assertEquals(1, response.interlude().log().size());
        assertEquals(1, response.interlude().log().getFirst().day());
    }

    @Test
    void readyResolutionCanAdvanceBeforeFourInterludes() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse(), turnResponse(1));
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse created = service.createSession(request());
        TextGameSessionResponse pending = submitChoice(service, created);

        executor.runNext();
        TextGameSessionResponse ready = service.getSession(pending.sessionId().toString());
        assertEquals("interlude", ready.phase());
        assertEquals("ready", ready.resolution().status());
        assertTrue(ready.resolution().canAdvance());
        assertEquals(0, ready.turn());

        TextGameSessionResponse response = service.advanceResolution(ready.sessionId().toString());

        assertEquals("decision", response.phase());
        assertEquals(1, response.turn());
        assertEquals(5, response.day());
        assertEquals("Turn 1", response.scene().title());
        assertEquals(0, response.stats().get("money"));
        assertEquals(49, response.stats().get("health"));
        assertEquals(31, response.stats().get("risk"));
        assertEquals("Result 1", response.lastResult());
    }

    @Test
    void readyResolutionWaitsForPlayerAdvanceAfterFourInterludes() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse(), turnResponse(1));
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse response = submitChoice(service, service.createSession(request()));

        executor.runNext();
        for (int i = 0; i < 4; i++) {
            response = submitInterlude(service, response, "work");
        }

        assertEquals("settling", response.phase());
        assertEquals("ready", response.resolution().status());
        assertTrue(response.resolution().canAdvance());
        assertEquals(0, response.turn());

        TextGameSessionResponse refreshed = service.getSession(response.sessionId().toString());
        assertEquals("settling", refreshed.phase());
        assertEquals(0, refreshed.turn());

        TextGameSessionResponse advanced = service.advanceResolution(response.sessionId().toString());
        assertEquals("decision", advanced.phase());
        assertEquals(1, advanced.turn());
        assertEquals(5, advanced.day());
        assertEquals(40, advanced.stats().get("money"));
    }

    @Test
    void fourInterludesEnterSettlingUntilResolutionCompletes() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse(), turnResponse(1));
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse response = submitChoice(service, service.createSession(request()));

        for (int i = 0; i < 4; i++) {
            response = submitInterlude(service, response, "work");
        }

        assertEquals("settling", response.phase());
        assertEquals("pending", response.resolution().status());
        assertEquals(0, response.turn());
        assertEquals(4, response.day());
        assertEquals(-60, response.stats().get("money"));
        assertEquals(1, response.interlude().actions().size());

        TextGameSessionResponse afterSettlingAction = submitInterlude(service, response, "settle");
        assertEquals("settling", afterSettlingAction.phase());
        assertEquals(response.day(), afterSettlingAction.day());
        assertEquals(response.stats(), afterSettlingAction.stats());
        assertEquals(5, afterSettlingAction.interlude().log().size());
        assertTrue(afterSettlingAction.interlude().log().getLast().settling());

        executor.runNext();
        TextGameSessionResponse committed = service.getSession(response.sessionId().toString());
        assertEquals("settling", committed.phase());
        assertEquals("ready", committed.resolution().status());
        assertTrue(committed.resolution().canAdvance());
        assertEquals(0, committed.turn());

        committed = service.advanceResolution(response.sessionId().toString());
        assertEquals("decision", committed.phase());
        assertEquals(1, committed.turn());
        assertEquals(5, committed.day());
        assertEquals(40, committed.stats().get("money"));
    }

    @Test
    void failedResolutionCanBeRetriedWithoutLosingInterludeState() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(
                openingResponse(),
                new RuntimeException("AI down"),
                turnResponse(1)
        );
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse response = submitChoice(service, service.createSession(request()));
        response = submitInterlude(service, response, "work");

        executor.runNext();
        TextGameSessionResponse failed = service.getSession(response.sessionId().toString());
        assertEquals("error", failed.phase());
        assertEquals("error", failed.resolution().status());
        assertEquals(1, failed.interlude().completedSteps());
        assertEquals(-90, failed.stats().get("money"));

        TextGameSessionResponse retrying = service.retryResolution(failed.sessionId().toString());
        assertEquals("interlude", retrying.phase());
        assertEquals("pending", retrying.resolution().status());
        assertEquals(1, retrying.interlude().completedSteps());

        executor.runNext();
        response = service.getSession(retrying.sessionId().toString());
        for (int i = response.interlude().completedSteps(); i < 4; i++) {
            response = submitInterlude(service, response, "work");
        }

        response = service.advanceResolution(response.sessionId().toString());
        assertEquals("decision", response.phase());
        assertEquals(1, response.turn());
        assertEquals(40, response.stats().get("money"));
    }

    @Test
    void invalidChoiceIdFailsBeforeSchedulingAi() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse());
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse created = service.createSession(request());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.submitChoice(
                        created.sessionId().toString(),
                        new SubmitTextGameChoiceRequest("Z", created.turn())
                )
        );

        assertEquals(1, aiTextClient.calls());
        assertEquals(0, executor.pendingTasks());
    }

    @Test
    void pendingResolutionCannotAdvanceAndKeepsInterludeState() {
        FakeAiTextClient aiTextClient = new FakeAiTextClient(openingResponse(), turnResponse(1));
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse response = submitChoice(service, service.createSession(request()));
        response = submitInterlude(service, response, "work");

        TextGameSessionResponse pending = response;
        assertThrows(
                TextGameConflictException.class,
                () -> service.advanceResolution(pending.sessionId().toString())
        );

        TextGameSessionResponse refreshed = service.getSession(response.sessionId().toString());
        assertEquals("interlude", refreshed.phase());
        assertEquals("pending", refreshed.resolution().status());
        assertFalse(refreshed.resolution().canAdvance());
        assertEquals(1, refreshed.interlude().completedSteps());
        assertEquals(-90, refreshed.stats().get("money"));
    }

    @Test
    void twentiethChoiceCompletesSessionAfterInterludes() {
        List<Object> responses = new ArrayList<>();
        responses.add(openingResponse());
        for (int i = 1; i < 20; i++) {
            responses.add(turnResponse(i));
        }
        responses.add(finalResponse());
        FakeAiTextClient aiTextClient = new FakeAiTextClient(responses.toArray());
        ManualExecutor executor = new ManualExecutor();
        TextGameService service = service(aiTextClient, executor);
        TextGameSessionResponse response = service.createSession(request());

        for (int i = 0; i < 20; i++) {
            response = submitChoice(service, response);
            executor.runNext();
            for (int step = 0; step < 4; step++) {
                response = submitInterlude(service, response, "work");
            }
            response = service.advanceResolution(response.sessionId().toString());
        }

        assertTrue(response.completed());
        assertEquals("completed", response.phase());
        assertEquals(20, response.turn());
        assertEquals(100, response.day());
        assertEquals("settlement_echo", response.stage().id());
        assertNotNull(response.ending());
        assertEquals("B", response.ending().grade());
        assertEquals(0, response.scene().choices().size());
        TextGameSessionResponse completedResponse = response;
        assertThrows(
                TextGameConflictException.class,
                () -> service.submitChoice(
                        completedResponse.sessionId().toString(),
                        new SubmitTextGameChoiceRequest("A", completedResponse.turn())
                )
        );
    }

    private static TextGameSessionResponse submitChoice(
            TextGameService service,
            TextGameSessionResponse response
    ) {
        return service.submitChoice(
                response.sessionId().toString(),
                new SubmitTextGameChoiceRequest("A", response.turn())
        );
    }

    private static TextGameSessionResponse submitInterlude(
            TextGameService service,
            TextGameSessionResponse response,
            String actionId
    ) {
        return service.submitInterludeAction(
                response.sessionId().toString(),
                new SubmitTextGameInterludeActionRequest(
                        actionId,
                        response.resolution().turn(),
                        response.interlude().nextStep()
                )
        );
    }

    private static TextGameService service(FakeAiTextClient aiTextClient, ManualExecutor executor) {
        return service(aiTextClient, executor, theme());
    }

    private static TextGameService service(
            FakeAiTextClient aiTextClient,
            ManualExecutor executor,
            TextGameThemeDefinition theme
    ) {
        return new TextGameService(
                aiTextClient,
                new TextGamePromptBuilder(),
                new TextGameResponseParser(),
                new TextGameDefinitionRegistry(List.of(theme), List.of(mode())),
                executor
        );
    }

    private static CreateTextGameSessionRequest request() {
        return new CreateTextGameSessionRequest("life_100_days", "short_20_turns");
    }

    private static TextGameThemeDefinition theme() {
        return new TextGameThemeDefinition(
                "life_100_days",
                "Life",
                "100 days",
                "Turn around a bad start.",
                "A worker with debt.",
                "grounded",
                Map.of(
                        "money", -100,
                        "health", 50,
                        "skill", 20,
                        "network", 10,
                        "reputation", 10,
                        "risk", 30
                ),
                Map.of(),
                List.of("Bound stats."),
                List.of("Rent is due."),
                List.of(
                        new TextGameActionDefinition(
                                "work",
                                "Work",
                                "Earn cash.",
                                Map.of("money", 10, "health", -1, "risk", 1),
                                List.of("Worked on day {day}."),
                                Map.of(),
                                Map.of()
                        ),
                        new TextGameActionDefinition(
                                "study",
                                "Study",
                                "Build skill.",
                                Map.of("skill", 3),
                                List.of("Studied."),
                                Map.of(),
                                Map.of()
                        )
                ),
                List.of(
                        new TextGameActionDefinition(
                                "settle",
                                "Settle",
                                "No stat change while waiting.",
                                Map.of("money", 999, "health", 999),
                                List.of("Settled."),
                                Map.of(),
                                Map.of()
                        )
                )
        );
    }

    private static TextGameThemeDefinition fiveActionTheme() {
        return new TextGameThemeDefinition(
                "life_100_days",
                "Life",
                "100 days",
                "Turn around a bad start.",
                "A worker with debt.",
                "grounded",
                Map.of(
                        "money", -100,
                        "health", 50,
                        "skill", 20,
                        "network", 10,
                        "reputation", 10,
                        "risk", 30
                ),
                Map.of(),
                List.of("Bound stats."),
                List.of("Rent is due."),
                List.of(
                        testAction("a"),
                        testAction("b"),
                        testAction("c"),
                        testAction("d"),
                        testAction("e")
                ),
                List.of(
                        new TextGameActionDefinition(
                                "settle",
                                "Settle",
                                "No stat change while waiting.",
                                Map.of(),
                                List.of("Settled."),
                                Map.of(),
                                Map.of()
                        )
                )
        );
    }

    private static TextGameThemeDefinition mixedActionTheme() {
        return new TextGameThemeDefinition(
                "life_100_days",
                "Life",
                "100 days",
                "Turn around a bad start.",
                "A worker with debt.",
                "grounded",
                Map.of(
                        "money", -3200,
                        "health", 50,
                        "skill", 20,
                        "network", 10,
                        "reputation", 10,
                        "risk", 30
                ),
                Map.of(),
                List.of("Bound stats."),
                List.of("Rent is due."),
                List.of(
                        new TextGameActionDefinition(
                                "cash_a",
                                "Cash A",
                                "Earn more cash.",
                                Map.of("money", 120),
                                List.of("Cash A."),
                                Map.of(),
                                Map.of()
                        ),
                        new TextGameActionDefinition(
                                "cash_b",
                                "Cash B",
                                "Earn cash.",
                                Map.of("money", 90),
                                List.of("Cash B."),
                                Map.of(),
                                Map.of()
                        ),
                        new TextGameActionDefinition(
                                "rest",
                                "Rest",
                                "Recover stability.",
                                Map.of("health", 6, "risk", -4),
                                List.of("Rested."),
                                Map.of(),
                                Map.of()
                        ),
                        new TextGameActionDefinition(
                                "study",
                                "Study",
                                "Build skill.",
                                Map.of("skill", 5),
                                List.of("Studied."),
                                Map.of(),
                                Map.of()
                        ),
                        new TextGameActionDefinition(
                                "social",
                                "Social",
                                "Build reputation and network.",
                                Map.of("network", 5, "reputation", 2),
                                List.of("Social."),
                                Map.of(),
                                Map.of()
                        )
                ),
                List.of(
                        new TextGameActionDefinition(
                                "settle",
                                "Settle",
                                "No stat change while waiting.",
                                Map.of(),
                                List.of("Settled."),
                                Map.of(),
                                Map.of()
                        )
                )
        );
    }

    private static TextGameActionDefinition testAction(String id) {
        return new TextGameActionDefinition(
                id,
                id.toUpperCase(),
                "Test action " + id,
                Map.of("money", 1),
                List.of("Action " + id + " on day {day}."),
                Map.of(),
                Map.of()
        );
    }

    private static List<String> actionIds(TextGameSessionResponse response) {
        return response.interlude().actions().stream()
                .map(TextGameActionDefinition::id)
                .toList();
    }

    private static TextGameModeDefinition mode() {
        return new TextGameModeDefinition(
                "short_20_turns",
                "Short",
                "20 turns",
                20,
                100,
                List.of(
                        new TextGameStageDefinition("opening_crisis", "Opening", 1, 5, "start"),
                        new TextGameStageDefinition("growth_split", "Growth", 6, 10, "middle"),
                        new TextGameStageDefinition("key_opportunity", "Opportunity", 11, 15, "chance"),
                        new TextGameStageDefinition("settlement_echo", "Settlement", 16, 20, "ending")
                )
        );
    }

    private static String openingResponse() {
        return """
                {
                  "scene": {
                    "title": "Opening",
                    "text": "Rent is due tonight.",
                    "choices": [
                      {"id": "A", "label": "Take a night shift", "hint": "cash"},
                      {"id": "B", "label": "Call an old classmate", "hint": "network"}
                    ]
                  }
                }
                """;
    }

    private static String turnResponse(int turn) {
        return """
                {
                  "result": "Result %d",
                  "statsDelta": {"money": 100, "health": -1, "skill": 1, "network": 0, "reputation": 0, "risk": 1},
                  "scene": {
                    "title": "Turn %d",
                    "text": "The next problem arrives.",
                    "choices": [
                      {"id": "A", "label": "Keep pushing", "hint": "cash"},
                      {"id": "B", "label": "Slow down", "hint": "health"}
                    ]
                  }
                }
                """.formatted(turn, turn);
    }

    private static String finalResponse() {
        return """
                {
                  "result": "The final choice lands.",
                  "statsDelta": {"money": 250, "health": 2, "skill": 3, "network": 1, "reputation": 4, "risk": -8},
                  "ending": {
                    "title": "Road Still Open",
                    "grade": "B",
                    "summary": "The debt pressure eased and a workable path appeared.",
                    "echoes": ["The first night shift bought time.", "The saved reputation kept a door open."]
                  }
                }
                """;
    }

    private static class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }

        int pendingTasks() {
            return tasks.size();
        }
    }

    private static class FakeAiTextClient implements AiTextClient {
        private final Queue<Object> responses = new ArrayDeque<>();
        private int calls;

        FakeAiTextClient(Object... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String generateJson(String prompt) {
            calls++;
            if (responses.isEmpty()) {
                throw new IllegalStateException("No fake AI response queued");
            }
            Object response = responses.remove();
            if (response instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return response.toString();
        }

        int calls() {
            return calls;
        }
    }
}
