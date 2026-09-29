package com.trade.textgame.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.trade.textgame.application.port.TextGameSessionStore;
import com.trade.textgame.domain.model.GameSession;
import com.trade.textgame.domain.model.GameState;
import com.trade.textgame.domain.model.StoryDocument;
import com.trade.textgame.domain.model.TextGameApi;
import com.trade.textgame.domain.rule.TextGameRuleEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds use-case views from typed state; has no storage or JSON codec dependency. */
final class TextGameSessionViews {
    private final TextGameRuleEngine ruleEngine;
    TextGameSessionViews(TextGameRuleEngine ruleEngine) { this.ruleEngine = ruleEngine; }

    TextGameApi.Session view(
            GameSession session,
            TextGameSessionStore.StoryVersion version,
            StoryDocument story,
            GameState state
    ) {
        JsonNode current = story.node(session.currentNodeId());
        TextGameApi.Scene sceneView = null;
        TextGameApi.Ending endingView = null;
        if ("scene".equals(session.phase())) {
            List<TextGameApi.Choice> choices = ruleEngine.visibleChoices(current, state).stream()
                    .map(choice -> choiceView(choice, state))
                    .toList();
            sceneView = new TextGameApi.Scene(
                    current.path("id").asText(),
                    current.path("title").asText(),
                    ruleEngine.resolveText(current, state, "text"),
                    choices
            );
        } else if ("completed".equals(session.phase())) {
            endingView = new TextGameApi.Ending(
                    current.path("id").asText(),
                    current.path("title").asText(),
                    current.path("grade").asText(),
                    ruleEngine.resolveText(current, state, "text"),
                    strings(current.path("echoes"))
            );
        }
        TextGameApi.ChoiceResult resultView = session.result() == null ? null : new TextGameApi.ChoiceResult(
                session.result().choiceId(), session.result().text(), new TextGameApi.EffectSummary(
                session.result().effects().attributeDelta(), session.result().effects().relationDelta(), session.result().effects().flagChanges()));
        JsonNode progressNode = current;
        JsonNode chapter = progressNode.path("chapter");
        TextGameApi.Progress progress = new TextGameApi.Progress(
                state.getHistory().size(),
                story.metadata().path("maxChoices").asInt(),
                chapter.path("number").asInt(story.metadata().path("chapterCount").asInt()),
                chapter.path("title").asText("终章"),
                progressNode.path("date").asText("")
        );
        return new TextGameApi.Session(
                session.sessionId(),
                new TextGameApi.StoryRef(version.storyKey(), version.title(), version.versionNumber()),
                session.revision(),
                session.phase(),
                progress,
                sceneView,
                resultView,
                endingView,
                Map.copyOf(state.getAttributes()),
                Map.copyOf(state.getRelations()),
                Map.copyOf(state.getFlags())
        );
    }

    private TextGameApi.Choice choiceView(JsonNode choice, GameState state) {
        boolean enabled = ruleEngine.matches(choice.path("enabledWhen"), state);
        return new TextGameApi.Choice(
                choice.path("id").asText(),
                choice.path("label").asText(),
                choice.path("hint").asText(null),
                enabled,
                enabled ? null : choice.path("disabledReason").asText("条件不足")
        );
    }

    TextGameApi.StorySummary summary(TextGameSessionStore.StoryVersion version) {
        StoryDocument story = version.story();
        JsonNode metadata = story.metadata();
        return new TextGameApi.StorySummary(
                version.storyKey(),
                version.title(),
                version.summary(),
                metadata.path("durationMinutes").asInt(),
                metadata.path("maxChoices").asInt(),
                strings(metadata.path("tags")),
                metadata.path("coverImage").asText(null),
                version.versionNumber()
        );
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> values.add(item.asText()));
        }
        return List.copyOf(values);
    }
}
