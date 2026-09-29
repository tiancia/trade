package com.trade.textgame.domain.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.trade.textgame.domain.model.GameState;
import com.trade.textgame.domain.model.StoryDocument;
import com.trade.textgame.domain.exception.TextGameConflictException;
import java.util.List;
import java.time.Instant;
/** Session phases and choice progression. Persistence and optimistic writes remain in the use case. */
public final class SessionProgression {
    private final TextGameRuleEngine ruleEngine;
    public SessionProgression(TextGameRuleEngine ruleEngine) { this.ruleEngine = ruleEngine; }
    public record ChoiceOutcome(String sourceNodeId, String targetNodeId, List<String> text, TextGameRuleEngine.EffectResult effects) {}
    public record Continued(String nodeId, String phase, Instant completedAt) {}
    public ChoiceOutcome choose(StoryDocument story, GameState state, String currentNodeId, String choiceId) {
        JsonNode scene = story.node(currentNodeId);
        JsonNode choice = findChoice(scene, choiceId);
        if (!ruleEngine.matches(choice.path("visibleWhen"), state)) {
            throw new IllegalArgumentException("选项当前不可见");
        }
        if (!ruleEngine.matches(choice.path("enabledWhen"), state)) {
            throw new TextGameConflictException(choice.path("disabledReason").asText("选项当前不可用"));
        }

        TextGameRuleEngine.EffectResult effects = ruleEngine.applyEffects(choice.path("effects"), state);
        String targetNodeId = ruleEngine.resolveTransition(choice, state);
        List<String> resultText = ruleEngine.resolveText(choice, state, "resultText");
        state.getHistory().add(choiceId);

        return new ChoiceOutcome(scene.path("id").asText(), targetNodeId, resultText, effects);
    }
    public static void requireChoicePhase(String phase) {
        if (!"scene".equals(phase)) { throw new TextGameConflictException("当前阶段不能提交选项"); }
    }
    public static void requirePendingResult(String phase, String pendingNodeId) {
        if (!"result".equals(phase) || pendingNodeId == null) { throw new TextGameConflictException("当前没有待确认的选择结果"); }
    }
    public static void requireRevision(long actual, long expected) {
        if (actual != expected) { throw new TextGameConflictException("页面版本已过期，请刷新存档"); }
    }
    public static Continued continueTo(StoryDocument story, String pendingNodeId, Instant now) {
        JsonNode next = story.node(pendingNodeId);
        boolean completed = "ending".equals(next.path("type").asText());
        return new Continued(pendingNodeId, completed ? "completed" : "scene", completed ? now : null);
    }
    private static JsonNode findChoice(JsonNode scene, String choiceId) {
        for (JsonNode choice : scene.path("choices")) {
            if (choiceId.equals(choice.path("id").asText())) {
                return choice;
            }
        }
        throw new IllegalArgumentException("当前场景不存在该选项: " + choiceId);
    }
}
