package com.trade.textgame.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.domain.StoryDocument;
import com.trade.textgame.domain.StoryValidation;
import com.trade.textgame.domain.TextGameStoryValidator;
import com.trade.textgame.model.TextGameAdminApi;
import com.trade.textgame.persistence.TextGameMapper;
import com.trade.textgame.persistence.TextGameStoryRow;
import com.trade.textgame.persistence.TextGameVersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Admin-side story version workflow for the text-game domain.
 *
 * <p>Draft creation, replacement, validation, publishing, and checksum updates
 * are kept here so the controller only handles HTTP and token checks.</p>
 */
@Service
public class TextGameAdminService {
    private final TextGameMapper mapper;
    private final ObjectMapper objectMapper;
    private final TextGameStoryValidator validator;

    public TextGameAdminService(TextGameMapper mapper, ObjectMapper objectMapper, TextGameStoryValidator validator) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public StoryValidation.Result validate(JsonNode story) {
        return validator.validate(story);
    }

    public List<TextGameAdminApi.StoryView> listStories() {
        return mapper.listStories().stream().map(story -> new TextGameAdminApi.StoryView(
                story.getStoryKey(),
                story.getTitle(),
                story.getSummary(),
                story.isEnabled(),
                story.getSortOrder(),
                mapper.listVersions(story.getId()).stream().map(this::versionSummary).toList()
        )).toList();
    }

    public TextGameAdminApi.VersionDocument getVersion(String storyKey, int versionNumber) {
        TextGameStoryRow story = requireStory(storyKey);
        TextGameVersionRow version = mapper.findVersion(story.getId(), versionNumber);
        if (version == null) {
            throw new TextGameNotFoundException("剧情版本不存在");
        }
        return document(version);
    }

    @Transactional
    public TextGameAdminApi.VersionDocument createDraft(
            String storyKey,
            TextGameAdminApi.CreateDraftRequest request
    ) {
        if (request == null || request.story() == null) {
            throw new IllegalArgumentException("story 不能为空");
        }
        requireValidStory(storyKey, request.story());
        StoryDocument document = StoryDocument.from(request.story());
        TextGameStoryRow story = mapper.findStoryByKey(storyKey);
        if (story == null) {
            story = new TextGameStoryRow()
                    .setStoryKey(storyKey)
                    .setTitle(document.metadata().path("title").asText())
                    .setSummary(document.metadata().path("summary").asText())
                    .setEnabled(true)
                    .setSortOrder(0);
            mapper.insertStory(story);
        }
        int versionNumber = request.versionNumber() == null
                ? mapper.listVersions(story.getId()).stream().mapToInt(TextGameVersionRow::getVersionNumber).max().orElse(0) + 1
                : request.versionNumber();
        if (versionNumber <= 0 || mapper.findVersion(story.getId(), versionNumber) != null) {
            throw new TextGameConflictException("剧情版本号无效或已存在");
        }
        String json = writeJson(request.story());
        TextGameVersionRow version = new TextGameVersionRow()
                .setStoryId(story.getId())
                .setVersionNumber(versionNumber)
                .setStatus("DRAFT")
                .setRevision(0)
                .setStoryJson(json)
                .setChecksum(checksum(json));
        mapper.insertVersion(version);
        return document(mapper.findVersionById(version.getId()));
    }

    @Transactional
    public TextGameAdminApi.VersionDocument replaceDraft(
            String storyKey,
            int versionNumber,
            TextGameAdminApi.ReplaceDraftRequest request
    ) {
        if (request == null || request.expectedRevision() == null || request.story() == null) {
            throw new IllegalArgumentException("expectedRevision 和 story 不能为空");
        }
        requireValidStory(storyKey, request.story());
        TextGameStoryRow story = requireStory(storyKey);
        TextGameVersionRow version = requireVersion(story, versionNumber);
        String json = writeJson(request.story());
        if (mapper.updateDraft(version.getId(), json, checksum(json), request.expectedRevision()) != 1) {
            throw new TextGameConflictException("草稿已修改或该版本不是草稿");
        }
        return document(mapper.findVersionById(version.getId()));
    }

    @Transactional
    public TextGameAdminApi.VersionDocument publish(
            String storyKey,
            int versionNumber,
            TextGameAdminApi.PublishRequest request
    ) {
        if (request == null || request.expectedRevision() == null) {
            throw new IllegalArgumentException("expectedRevision 不能为空");
        }
        TextGameStoryRow story = requireStory(storyKey);
        TextGameVersionRow version = requireVersion(story, versionNumber);
        StoryValidation.Result validation = validator.validate(readJson(version.getStoryJson()));
        if (!validation.valid()) {
            throw new IllegalArgumentException("剧情校验失败: " + validation.errors().getFirst().message());
        }
        mapper.archivePublished(story.getId());
        if (mapper.publishDraft(version.getId(), request.expectedRevision(), Timestamp.from(Instant.now())) != 1) {
            throw new TextGameConflictException("草稿已修改或该版本不能发布");
        }
        StoryDocument document = StoryDocument.parse(objectMapper, version.getStoryJson());
        story.setTitle(document.metadata().path("title").asText())
                .setSummary(document.metadata().path("summary").asText());
        mapper.updateStoryMetadata(story);
        return document(mapper.findVersionById(version.getId()));
    }

    private void requireValidStory(String storyKey, JsonNode value) {
        StoryValidation.Result result = validator.validate(value);
        if (!result.valid()) {
            throw new IllegalArgumentException("剧情校验失败: " + result.errors().getFirst().message());
        }
        if (!storyKey.equals(value.path("storyKey").asText())) {
            throw new IllegalArgumentException("URL storyKey 与剧情 JSON 不一致");
        }
    }

    private TextGameStoryRow requireStory(String storyKey) {
        TextGameStoryRow story = mapper.findStoryByKey(storyKey);
        if (story == null) {
            throw new TextGameNotFoundException("剧情不存在: " + storyKey);
        }
        return story;
    }

    private TextGameVersionRow requireVersion(TextGameStoryRow story, int versionNumber) {
        TextGameVersionRow version = mapper.findVersion(story.getId(), versionNumber);
        if (version == null) {
            throw new TextGameNotFoundException("剧情版本不存在");
        }
        return version;
    }

    private TextGameAdminApi.VersionSummary versionSummary(TextGameVersionRow version) {
        return new TextGameAdminApi.VersionSummary(
                version.getVersionNumber(),
                version.getStatus(),
                version.getRevision(),
                version.getChecksum(),
                version.getPublishedAt() == null ? null : version.getPublishedAt().toInstant()
        );
    }

    private TextGameAdminApi.VersionDocument document(TextGameVersionRow version) {
        return new TextGameAdminApi.VersionDocument(
                version.getStoryKey(),
                version.getVersionNumber(),
                version.getStatus(),
                version.getRevision(),
                version.getChecksum(),
                readJson(version.getStoryJson())
        );
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("数据库中的剧情 JSON 已损坏", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("剧情 JSON 无法序列化", e);
        }
    }

    static String checksum(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法计算剧情校验和", e);
        }
    }
}
