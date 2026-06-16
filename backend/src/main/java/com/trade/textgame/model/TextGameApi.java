package com.trade.textgame.model;

import java.util.List;
import java.util.Map;

public final class TextGameApi {
    private TextGameApi() {
    }

    public record Catalog(List<StorySummary> stories) {
    }

    public record StorySummary(
            String storyKey,
            String title,
            String summary,
            int durationMinutes,
            int maxChoices,
            List<String> tags,
            String coverImage,
            int version
    ) {
    }

    public record CreateSessionRequest(String storyKey) {
    }

    public record SubmitChoiceRequest(String choiceId, Long expectedRevision) {
    }

    public record ContinueRequest(Long expectedRevision) {
    }

    public record Session(
            String sessionId,
            StoryRef story,
            long revision,
            String phase,
            Progress progress,
            Scene scene,
            ChoiceResult result,
            Ending ending,
            Map<String, Integer> attributes,
            Map<String, Integer> relations,
            Map<String, Object> flags
    ) {
    }

    public record StoryRef(String storyKey, String title, int version) {
    }

    public record Progress(int turn, int maxTurns, int chapterNumber, String chapterTitle, String date) {
    }

    public record Scene(String nodeId, String title, List<String> text, List<Choice> choices) {
    }

    public record Choice(String id, String label, String hint, boolean enabled, String disabledReason) {
    }

    public record ChoiceResult(String choiceId, List<String> text, EffectSummary effects) {
    }

    public record EffectSummary(
            Map<String, Integer> attributes,
            Map<String, Integer> relations,
            Map<String, Object> flags
    ) {
    }

    public record Ending(String nodeId, String title, String grade, List<String> text, List<String> echoes) {
    }
}
