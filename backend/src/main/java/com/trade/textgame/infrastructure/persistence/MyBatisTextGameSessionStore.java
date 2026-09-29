package com.trade.textgame.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.application.port.TextGameSessionStore;
import com.trade.textgame.domain.exception.TextGameConflictException;
import com.trade.textgame.domain.model.GameSession;
import com.trade.textgame.domain.model.GameState;
import com.trade.textgame.domain.model.StoryDocument;
import com.trade.textgame.domain.rule.TextGameRuleEngine;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Owns row mapping and the existing save/event JSON format. Transactions belong to the use case. */
@Component
public class MyBatisTextGameSessionStore implements TextGameSessionStore {
    private static final TypeReference<LinkedHashMap<String, Integer>> INT_MAP = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final TypeReference<ArrayList<String>> STRING_LIST = new TypeReference<>() {};
    private final TextGameMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisTextGameSessionStore(TextGameMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<StoryVersion> publishedCatalog() { return mapper.findPublishedCatalog().stream().map(this::version).toList(); }
    @Override
    public Optional<StoryVersion> latestPublished(String key) { return Optional.ofNullable(mapper.findLatestPublished(key)).map(this::version); }
    @Override
    public Optional<StoryVersion> findVersion(long id) { return Optional.ofNullable(mapper.findVersionById(id)).map(this::version); }
    @Override
    public Optional<GameSession> find(String id) { return Optional.ofNullable(mapper.findSession(id)).map(this::session); }
    @Override
    public void insert(GameSession session) { mapper.insertSession(row(session)); }
    @Override
    public GameSession update(GameSession session, Instant expiresAt) {
        var row = row(session).setExpiresAt(Timestamp.from(expiresAt));
        if (mapper.updateSession(row) != 1) { throw new TextGameConflictException("存档已被其他请求更新，请刷新后重试"); }
        return new GameSession(session.sessionId(), session.storyVersionId(), session.currentNodeId(), session.pendingNodeId(),
                session.phase(), session.state(), session.result(), session.revision() + 1, expiresAt, session.completedAt());
    }
    @Override
    public void recordChoice(GameSession session) {
        mapper.insertSessionEvent(new TextGameSessionEventRow().setSessionId(session.sessionId())
                .setSequenceNo(session.state().getHistory().size()).setNodeId(session.currentNodeId())
                .setChoiceId(session.result().choiceId()).setEffectsJson(writeJson(session.result().effects()))
                .setStateAfterJson(writeJson(session.state())));
    }
    @Override
    public void delete(String id) { mapper.deleteSession(id); }

    private StoryVersion version(TextGameVersionRow row) {
        return new StoryVersion(row.getId(), row.getStoryKey(), row.getTitle(), row.getSummary(), row.getVersionNumber(),
                StoryDocument.parse(objectMapper, row.getStoryJson()));
    }

    private GameSession session(TextGameSessionRow row) {
        GameState state;
        try {
            state = new GameState();
            state.setAttributes(objectMapper.readValue(row.getAttributesJson(), INT_MAP));
            state.setRelations(objectMapper.readValue(row.getRelationsJson(), INT_MAP));
            state.setFlags(objectMapper.readValue(row.getFlagsJson(), OBJECT_MAP));
            state.setHistory(objectMapper.readValue(row.getHistoryJson(), STRING_LIST));
        } catch (Exception e) { throw new IllegalStateException("文字游戏存档数据损坏", e); }
        return new GameSession(row.getSessionId(), row.getStoryVersionId(), row.getCurrentNodeId(), row.getPendingNodeId(),
                row.getPhase(), state, readResult(row.getResultJson()), row.getRevision(), row.getExpiresAt().toInstant(),
                row.getCompletedAt() == null ? null : row.getCompletedAt().toInstant());
    }

    private TextGameSessionRow row(GameSession session) {
        return new TextGameSessionRow().setSessionId(session.sessionId()).setStoryVersionId(session.storyVersionId())
                .setCurrentNodeId(session.currentNodeId()).setPendingNodeId(session.pendingNodeId()).setPhase(session.phase())
                .setRevision(session.revision()).setExpiresAt(Timestamp.from(session.expiresAt()))
                .setCompletedAt(session.completedAt() == null ? null : Timestamp.from(session.completedAt()))
                .setAttributesJson(writeJson(session.state().getAttributes())).setRelationsJson(writeJson(session.state().getRelations()))
                .setFlagsJson(writeJson(session.state().getFlags())).setHistoryJson(writeJson(session.state().getHistory()))
                .setResultJson(session.result() == null ? null : writeJson(session.result()));
    }

    private GameSession.ChoiceResult readResult(String json) {
        if (json == null || json.isBlank()) { return null; }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode effects = node.path("effects");
            return new GameSession.ChoiceResult(node.path("choiceId").asText(), strings(node.path("text")),
                    new TextGameRuleEngine.EffectResult(objectMapper.convertValue(effects.path("attributeDelta"), INT_MAP),
                            objectMapper.convertValue(effects.path("relationDelta"), INT_MAP),
                            objectMapper.convertValue(effects.path("flagChanges"), OBJECT_MAP)));
        } catch (Exception e) { throw new IllegalStateException("存档结果数据损坏", e); }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法序列化文字游戏数据", e); }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> values.add(item.asText()));
        }
        return List.copyOf(values);
    }
}
