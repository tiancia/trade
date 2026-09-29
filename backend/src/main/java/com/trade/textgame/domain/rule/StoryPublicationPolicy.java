package com.trade.textgame.domain.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.trade.textgame.domain.model.StoryValidation;
import com.trade.textgame.domain.exception.TextGameConflictException;
/** Publication validity and immutable version identity rules. */
public final class StoryPublicationPolicy {
    private final TextGameStoryValidator validator;
    public StoryPublicationPolicy(TextGameStoryValidator validator) { this.validator = validator; }
    public void requireValidStory(String storyKey, JsonNode value) {
        StoryValidation.Result result = validator.validate(value);
        if (!result.valid()) {
            throw new IllegalArgumentException("剧情校验失败: " + result.errors().getFirst().message());
        }
        if (!storyKey.equals(value.path("storyKey").asText())) {
            throw new IllegalArgumentException("URL storyKey 与剧情 JSON 不一致");
        }
    }
    public static void requireAvailableVersion(int versionNumber, boolean exists) {
        if (versionNumber <= 0 || exists) { throw new TextGameConflictException("剧情版本号无效或已存在"); }
    }
    public void requirePublishable(JsonNode story) {
        StoryValidation.Result result = validator.validate(story);
        if (!result.valid()) { throw new IllegalArgumentException("剧情校验失败: " + result.errors().getFirst().message()); }
    }
}
