package com.trade.textgame.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.config.TextGameProperties;
import com.trade.textgame.domain.StoryDocument;
import com.trade.textgame.domain.StoryValidation;
import com.trade.textgame.domain.TextGameStoryValidator;
import com.trade.textgame.persistence.TextGameMapper;
import com.trade.textgame.persistence.TextGameStoryRow;
import com.trade.textgame.persistence.TextGameVersionRow;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

@Component
public class TextGameSeedImporter implements ApplicationRunner {
    private final TextGameMapper mapper;
    private final ObjectMapper objectMapper;
    private final TextGameStoryValidator validator;
    private final TextGameProperties properties;

    public TextGameSeedImporter(
            TextGameMapper mapper,
            ObjectMapper objectMapper,
            TextGameStoryValidator validator,
            TextGameProperties properties
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isSeedEnabled()) {
            return;
        }
        String json = new ClassPathResource("textgame/stories/100-days-comeback.v1.json")
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(json);
        StoryValidation.Result validation = validator.validate(root);
        if (!validation.valid()) {
            throw new IllegalStateException("内置文字游戏剧情无效: " + validation.errors().getFirst().message());
        }
        StoryDocument document = StoryDocument.from(root);
        TextGameStoryRow story = mapper.findStoryByKey(document.storyKey());
        if (story == null) {
            story = new TextGameStoryRow()
                    .setStoryKey(document.storyKey())
                    .setTitle(document.metadata().path("title").asText())
                    .setSummary(document.metadata().path("summary").asText())
                    .setEnabled(true)
                    .setSortOrder(10);
            mapper.insertStory(story);
        }
        if (mapper.findVersion(story.getId(), 1) != null) {
            return;
        }
        mapper.insertVersion(new TextGameVersionRow()
                .setStoryId(story.getId())
                .setVersionNumber(1)
                .setStatus("PUBLISHED")
                .setRevision(0)
                .setStoryJson(json)
                .setChecksum(TextGameAdminService.checksum(json))
                .setPublishedAt(Timestamp.from(Instant.now())));
    }
}
