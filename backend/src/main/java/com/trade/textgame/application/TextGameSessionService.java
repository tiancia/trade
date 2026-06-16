package com.trade.textgame.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trade.textgame.config.TextGameProperties;
import com.trade.textgame.domain.GameState;
import com.trade.textgame.domain.StoryDocument;
import com.trade.textgame.domain.TextGameRuleEngine;
import com.trade.textgame.model.TextGameApi;
import com.trade.textgame.persistence.TextGameMapper;
import com.trade.textgame.persistence.TextGameSessionEventRow;
import com.trade.textgame.persistence.TextGameSessionRow;
import com.trade.textgame.persistence.TextGameVersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TextGameSessionService {
    private static final TypeReference<LinkedHashMap<String, Integer>> INT_MAP = new TypeReference<>() { };
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final TypeReference<ArrayList<String>> STRING_LIST = new TypeReference<>() { };

    private final TextGameMapper mapper;
    private final ObjectMapper objectMapper;
    private final TextGameRuleEngine ruleEngine;
    private final TextGameProperties properties;

    public TextGameSessionService(
            TextGameMapper mapper,
            ObjectMapper objectMapper,
            TextGameRuleEngine ruleEngine,
            TextGameProperties properties
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.ruleEngine = ruleEngine;
        this.properties = properties;
    }

    public TextGameApi.Catalog catalog() {
        List<TextGameApi.StorySummary> stories = mapper.findPublishedCatalog().stream()
                .map(this::summary)
                .toList();
        return new TextGameApi.Catalog(stories);
    }

    @Transactional
    public TextGameApi.Session createSession(TextGameApi.CreateSessionRequest request) {
        String storyKey = request == null ? null : request.storyKey();
        if (storyKey == null || storyKey.isBlank()) {
            throw new IllegalArgumentException("storyKey 不能为空");
        }
        TextGameVersionRow version = mapper.findLatestPublished(storyKey);
        if (version == null) {
            throw new TextGameNotFoundException("没有可用的已发布剧情: " + storyKey);
        }
        StoryDocument story = StoryDocument.parse(objectMapper, version.getStoryJson());
        GameState state = initialState(story);
        TextGameSessionRow row = new TextGameSessionRow()
                .setSessionId(UUID.randomUUID().toString())
                .setStoryVersionId(version.getId())
                .setCurrentNodeId(story.startNodeId())
                .setPhase("scene")
                .setRevision(0)
                .setExpiresAt(nextExpiry());
        writeState(row, state);
        mapper.insertSession(row);
        return view(row, version, story, state);
    }

    public TextGameApi.Session getSession(String sessionId) {
        TextGameSessionRow row = requireSession(sessionId);
        TextGameVersionRow version = requireVersion(row.getStoryVersionId());
        StoryDocument story = StoryDocument.parse(objectMapper, version.getStoryJson());
        return view(row, version, story, readState(row));
    }

    @Transactional
    public TextGameApi.Session submitChoice(String sessionId, TextGameApi.SubmitChoiceRequest request) {
        if (request == null || request.choiceId() == null || request.choiceId().isBlank()
                || request.expectedRevision() == null) {
            throw new IllegalArgumentException("choiceId 和 expectedRevision 不能为空");
        }
        TextGameSessionRow row = requireSession(sessionId);
        requireRevision(row, request.expectedRevision());
        if (!"scene".equals(row.getPhase())) {
            throw new TextGameConflictException("当前阶段不能提交选项");
        }
        TextGameVersionRow version = requireVersion(row.getStoryVersionId());
        StoryDocument story = StoryDocument.parse(objectMapper, version.getStoryJson());
        GameState state = readState(row);
        JsonNode scene = story.node(row.getCurrentNodeId());
        JsonNode choice = findChoice(scene, request.choiceId());
        if (!ruleEngine.matches(choice.path("visibleWhen"), state)) {
            throw new IllegalArgumentException("选项当前不可见");
        }
        if (!ruleEngine.matches(choice.path("enabledWhen"), state)) {
            throw new TextGameConflictException(choice.path("disabledReason").asText("选项当前不可用"));
        }

        TextGameRuleEngine.EffectResult effects = ruleEngine.applyEffects(choice.path("effects"), state);
        String targetNodeId = ruleEngine.resolveTransition(choice, state);
        List<String> resultText = ruleEngine.resolveText(choice, state, "resultText");
        state.getHistory().add(request.choiceId());

        ObjectNode result = objectMapper.createObjectNode();
        result.put("choiceId", request.choiceId());
        result.set("text", objectMapper.valueToTree(resultText));
        result.set("effects", objectMapper.valueToTree(effects));
        row.setPendingNodeId(targetNodeId).setPhase("result").setResultJson(writeJson(result));
        writeState(row, state);
        persistUpdate(row);

        mapper.insertSessionEvent(new TextGameSessionEventRow()
                .setSessionId(sessionId)
                .setSequenceNo(state.getHistory().size())
                .setNodeId(scene.path("id").asText())
                .setChoiceId(request.choiceId())
                .setEffectsJson(writeJson(effects))
                .setStateAfterJson(writeJson(state)));
        return view(row, version, story, state);
    }

    @Transactional
    public TextGameApi.Session continueGame(String sessionId, TextGameApi.ContinueRequest request) {
        if (request == null || request.expectedRevision() == null) {
            throw new IllegalArgumentException("expectedRevision 不能为空");
        }
        TextGameSessionRow row = requireSession(sessionId);
        requireRevision(row, request.expectedRevision());
        if (!"result".equals(row.getPhase()) || row.getPendingNodeId() == null) {
            throw new TextGameConflictException("当前没有待确认的选择结果");
        }
        TextGameVersionRow version = requireVersion(row.getStoryVersionId());
        StoryDocument story = StoryDocument.parse(objectMapper, version.getStoryJson());
        JsonNode next = story.node(row.getPendingNodeId());
        boolean completed = "ending".equals(next.path("type").asText());
        row.setCurrentNodeId(row.getPendingNodeId())
                .setPendingNodeId(null)
                .setResultJson(null)
                .setPhase(completed ? "completed" : "scene")
                .setCompletedAt(completed ? Timestamp.from(Instant.now()) : null);
        persistUpdate(row);
        return view(row, version, story, readState(row));
    }

    @Transactional
    public void deleteSession(String sessionId) {
        mapper.deleteSession(sessionId);
    }

    private TextGameApi.Session view(
            TextGameSessionRow row,
            TextGameVersionRow version,
            StoryDocument story,
            GameState state
    ) {
        JsonNode current = story.node(row.getCurrentNodeId());
        TextGameApi.Scene sceneView = null;
        TextGameApi.Ending endingView = null;
        if ("scene".equals(row.getPhase())) {
            List<TextGameApi.Choice> choices = ruleEngine.visibleChoices(current, state).stream()
                    .map(choice -> choiceView(choice, state))
                    .toList();
            sceneView = new TextGameApi.Scene(
                    current.path("id").asText(),
                    current.path("title").asText(),
                    ruleEngine.resolveText(current, state, "text"),
                    choices
            );
        } else if ("completed".equals(row.getPhase())) {
            endingView = new TextGameApi.Ending(
                    current.path("id").asText(),
                    current.path("title").asText(),
                    current.path("grade").asText(),
                    ruleEngine.resolveText(current, state, "text"),
                    strings(current.path("echoes"))
            );
        }
        TextGameApi.ChoiceResult resultView = readChoiceResult(row.getResultJson());
        JsonNode progressNode = "result".equals(row.getPhase()) ? current : current;
        JsonNode chapter = progressNode.path("chapter");
        TextGameApi.Progress progress = new TextGameApi.Progress(
                state.getHistory().size(),
                story.metadata().path("maxChoices").asInt(),
                chapter.path("number").asInt(story.metadata().path("chapterCount").asInt()),
                chapter.path("title").asText("终章"),
                progressNode.path("date").asText("")
        );
        return new TextGameApi.Session(
                row.getSessionId(),
                new TextGameApi.StoryRef(version.getStoryKey(), version.getTitle(), version.getVersionNumber()),
                row.getRevision(),
                row.getPhase(),
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

    private TextGameApi.ChoiceResult readChoiceResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode effects = node.path("effects");
            return new TextGameApi.ChoiceResult(
                    node.path("choiceId").asText(),
                    strings(node.path("text")),
                    new TextGameApi.EffectSummary(
                            objectMapper.convertValue(effects.path("attributeDelta"), INT_MAP),
                            objectMapper.convertValue(effects.path("relationDelta"), INT_MAP),
                            objectMapper.convertValue(effects.path("flagChanges"), OBJECT_MAP)
                    )
            );
        } catch (Exception e) {
            throw new IllegalStateException("存档结果数据损坏", e);
        }
    }

    private TextGameApi.StorySummary summary(TextGameVersionRow version) {
        StoryDocument story = StoryDocument.parse(objectMapper, version.getStoryJson());
        JsonNode metadata = story.metadata();
        return new TextGameApi.StorySummary(
                version.getStoryKey(),
                version.getTitle(),
                version.getSummary(),
                metadata.path("durationMinutes").asInt(),
                metadata.path("maxChoices").asInt(),
                strings(metadata.path("tags")),
                metadata.path("coverImage").asText(null),
                version.getVersionNumber()
        );
    }

    private GameState initialState(StoryDocument story) {
        GameState state = new GameState();
        JsonNode initial = story.initialState();
        state.setAttributes(objectMapper.convertValue(initial.path("attributes"), INT_MAP));
        state.setRelations(objectMapper.convertValue(initial.path("relations"), INT_MAP));
        state.setFlags(objectMapper.convertValue(initial.path("flags"), OBJECT_MAP));
        return state;
    }

    private GameState readState(TextGameSessionRow row) {
        try {
            GameState state = new GameState();
            state.setAttributes(objectMapper.readValue(row.getAttributesJson(), INT_MAP));
            state.setRelations(objectMapper.readValue(row.getRelationsJson(), INT_MAP));
            state.setFlags(objectMapper.readValue(row.getFlagsJson(), OBJECT_MAP));
            state.setHistory(objectMapper.readValue(row.getHistoryJson(), STRING_LIST));
            return state;
        } catch (Exception e) {
            throw new IllegalStateException("文字游戏存档数据损坏", e);
        }
    }

    private void writeState(TextGameSessionRow row, GameState state) {
        row.setAttributesJson(writeJson(state.getAttributes()))
                .setRelationsJson(writeJson(state.getRelations()))
                .setFlagsJson(writeJson(state.getFlags()))
                .setHistoryJson(writeJson(state.getHistory()));
    }

    private void persistUpdate(TextGameSessionRow row) {
        row.setExpiresAt(nextExpiry());
        if (mapper.updateSession(row) != 1) {
            throw new TextGameConflictException("存档已被其他请求更新，请刷新后重试");
        }
        row.setRevision(row.getRevision() + 1);
    }

    private TextGameSessionRow requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new TextGameNotFoundException("存档不存在");
        }
        TextGameSessionRow row = mapper.findSession(sessionId);
        if (row == null || row.getExpiresAt().toInstant().isBefore(Instant.now())) {
            throw new TextGameNotFoundException("存档不存在或已过期");
        }
        return row;
    }

    private TextGameVersionRow requireVersion(long id) {
        TextGameVersionRow version = mapper.findVersionById(id);
        if (version == null) {
            throw new IllegalStateException("存档绑定的剧情版本不存在");
        }
        return version;
    }

    private static void requireRevision(TextGameSessionRow row, long expected) {
        if (row.getRevision() != expected) {
            throw new TextGameConflictException("页面版本已过期，请刷新存档");
        }
    }

    private static JsonNode findChoice(JsonNode scene, String choiceId) {
        for (JsonNode choice : scene.path("choices")) {
            if (choiceId.equals(choice.path("id").asText())) {
                return choice;
            }
        }
        throw new IllegalArgumentException("当前场景不存在该选项: " + choiceId);
    }

    private Timestamp nextExpiry() {
        return Timestamp.from(Instant.now().plus(properties.getSessionRetentionDays(), ChronoUnit.DAYS));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化文字游戏数据", e);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> values.add(item.asText()));
        }
        return List.copyOf(values);
    }
}
