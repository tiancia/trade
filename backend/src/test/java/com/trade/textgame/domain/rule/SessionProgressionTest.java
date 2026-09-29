package com.trade.textgame.domain.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.domain.exception.TextGameConflictException;
import com.trade.textgame.domain.model.GameState;
import com.trade.textgame.domain.model.StoryDocument;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionProgressionTest {
    private final SessionProgression progression = new SessionProgression(new TextGameRuleEngine());

    @Test
    void appliesEffectsBeforeBranchingButAddsHistoryAfterResolvingText() {
        StoryDocument story = StoryDocument.parse(new ObjectMapper(), """
                {"nodes":[{"id":"start","choices":[{"id":"learn",
                  "effects":[{"target":"attribute","key":"skill","op":"add","value":2}],
                  "transitions":[{"when":{"source":"attribute","key":"skill","op":"gte","value":2},"to":"win"},{"to":"lose"}],
                  "resultTextVariants":[{"when":{"source":"history","key":"learn","op":"contains","value":true},"text":["again"]}],
                  "resultText":["first"]}]},{"id":"win","type":"ending"}]}
                """);
        GameState state = new GameState();
        var outcome = progression.choose(story, state, "start", "learn");
        assertEquals("win", outcome.targetNodeId());
        assertEquals(List.of("first"), outcome.text());
        assertEquals(2, state.getAttributes().get("skill"));
        assertEquals(List.of("learn"), state.getHistory());
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        var continued = SessionProgression.continueTo(story, "win", now);
        assertEquals("completed", continued.phase());
        assertEquals(now, continued.completedAt());
    }

    @Test
    void hiddenAndDisabledChoicesCannotApplyEffects() {
        StoryDocument story = StoryDocument.parse(new ObjectMapper(), """
                {"nodes":[{"id":"start","choices":[
                {"id":"hidden","visibleWhen":{"source":"flag","key":"allow","op":"eq","value":true},
                 "effects":[{"target":"attribute","key":"money","op":"add","value":5}]},
                {"id":"disabled","enabledWhen":{"source":"flag","key":"allow","op":"eq","value":true},
                 "disabledReason":"locked","effects":[{"target":"attribute","key":"money","op":"add","value":5}]}]}]}
                """);
        GameState state = new GameState();
        assertThrows(IllegalArgumentException.class, () -> progression.choose(story, state, "start", "hidden"));
        assertEquals("locked", assertThrows(TextGameConflictException.class,
                () -> progression.choose(story, state, "start", "disabled")).getMessage());
        assertTrue(state.getAttributes().isEmpty());
        assertTrue(state.getHistory().isEmpty());
    }

    @Test
    void rejectsStaleRevisionAndWrongPhase() {
        assertThrows(TextGameConflictException.class, () -> SessionProgression.requireRevision(3, 2));
        assertThrows(TextGameConflictException.class, () -> SessionProgression.requireChoicePhase("result"));
        assertThrows(TextGameConflictException.class, () -> SessionProgression.requirePendingResult("result", null));
        assertDoesNotThrow(() -> SessionProgression.requireRevision(3, 3));
    }
}
