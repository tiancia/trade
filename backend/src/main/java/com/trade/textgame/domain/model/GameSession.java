package com.trade.textgame.domain.model;

import com.trade.textgame.domain.rule.SessionProgression;
import com.trade.textgame.domain.rule.TextGameRuleEngine;
import java.time.Instant;
import java.util.List;

/** Runtime session state, independent of database columns and saved JSON. */
public record GameSession(String sessionId, long storyVersionId, String currentNodeId, String pendingNodeId,
        String phase, GameState state, ChoiceResult result, long revision, Instant expiresAt, Instant completedAt) {
    public record ChoiceResult(String choiceId, List<String> text, TextGameRuleEngine.EffectResult effects) {}

    public static GameSession start(String id, long versionId, StoryDocument story, GameState state, Instant expiresAt) {
        return new GameSession(id, versionId, story.startNodeId(), null, "scene", state, null, 0, expiresAt, null);
    }

    public GameSession choose(StoryDocument story, TextGameRuleEngine rules, String choiceId, long expectedRevision) {
        SessionProgression.requireRevision(revision, expectedRevision);
        SessionProgression.requireChoicePhase(phase);
        GameState nextState = copyState();
        var outcome = new SessionProgression(rules).choose(story, nextState, currentNodeId, choiceId);
        return new GameSession(sessionId, storyVersionId, currentNodeId, outcome.targetNodeId(), "result", nextState,
                new ChoiceResult(choiceId, outcome.text(), outcome.effects()), revision, expiresAt, completedAt);
    }

    public GameSession continueGame(StoryDocument story, long expectedRevision, Instant now) {
        SessionProgression.requireRevision(revision, expectedRevision);
        SessionProgression.requirePendingResult(phase, pendingNodeId);
        var continued = SessionProgression.continueTo(story, pendingNodeId, now);
        return new GameSession(sessionId, storyVersionId, continued.nodeId(), null, continued.phase(), state, null,
                revision, expiresAt, continued.completedAt());
    }

    private GameState copyState() {
        GameState copy = new GameState();
        copy.setAttributes(state.getAttributes());
        copy.setRelations(state.getRelations());
        copy.setFlags(state.getFlags());
        copy.setHistory(state.getHistory());
        return copy;
    }
}
