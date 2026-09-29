package com.trade.textgame.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.textgame.application.port.TextGameSessionStore;
import com.trade.textgame.domain.exception.TextGameNotFoundException;
import com.trade.textgame.domain.model.GameSession;
import com.trade.textgame.domain.model.GameState;
import com.trade.textgame.domain.model.StoryDocument;
import com.trade.textgame.domain.model.TextGameApi;
import com.trade.textgame.domain.rule.TextGameRuleEngine;
import com.trade.textgame.infrastructure.config.TextGameProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Coordinates session loading, domain progression, optimistic persistence and event delivery. */
@Service
public class TextGameSessionService {
    private static final TypeReference<LinkedHashMap<String, Integer>> INT_MAP = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private final TextGameSessionStore store;
    private final ObjectMapper objectMapper;
    private final TextGameRuleEngine ruleEngine;
    private final TextGameProperties properties;
    private final TextGameSessionViews views;

    public TextGameSessionService(TextGameSessionStore store, ObjectMapper objectMapper,
            TextGameRuleEngine ruleEngine, TextGameProperties properties) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.ruleEngine = ruleEngine;
        this.properties = properties;
        this.views = new TextGameSessionViews(ruleEngine);
    }

    public TextGameApi.Catalog catalog() {
        return new TextGameApi.Catalog(store.publishedCatalog().stream().map(views::summary).toList());
    }

    @Transactional
    public TextGameApi.Session createSession(TextGameApi.CreateSessionRequest request) {
        String storyKey = request == null ? null : request.storyKey();
        if (storyKey == null || storyKey.isBlank()) { throw new IllegalArgumentException("storyKey 不能为空"); }
        var version = store.latestPublished(storyKey)
                .orElseThrow(() -> new TextGameNotFoundException("没有可用的已发布剧情: " + storyKey));
        var session = GameSession.start(UUID.randomUUID().toString(), version.id(), version.story(),
                initialState(version.story()), nextExpiry());
        store.insert(session);
        return views.view(session, version, version.story(), session.state());
    }

    public TextGameApi.Session getSession(String sessionId) {
        var session = requireSession(sessionId);
        var version = requireVersion(session.storyVersionId());
        return views.view(session, version, version.story(), session.state());
    }

    @Transactional
    public TextGameApi.Session submitChoice(String sessionId, TextGameApi.SubmitChoiceRequest request) {
        if (request == null || request.choiceId() == null || request.choiceId().isBlank()
                || request.expectedRevision() == null) {
            throw new IllegalArgumentException("choiceId 和 expectedRevision 不能为空");
        }
        var session = requireSession(sessionId);
        var version = requireVersion(session.storyVersionId());
        var next = session.choose(version.story(), ruleEngine, request.choiceId(), request.expectedRevision());
        var saved = store.update(next, nextExpiry());
        store.recordChoice(saved);
        return views.view(saved, version, version.story(), saved.state());
    }

    @Transactional
    public TextGameApi.Session continueGame(String sessionId, TextGameApi.ContinueRequest request) {
        if (request == null || request.expectedRevision() == null) {
            throw new IllegalArgumentException("expectedRevision 不能为空");
        }
        var session = requireSession(sessionId);
        var version = requireVersion(session.storyVersionId());
        var saved = store.update(session.continueGame(version.story(), request.expectedRevision(), Instant.now()), nextExpiry());
        return views.view(saved, version, version.story(), saved.state());
    }

    @Transactional
    public void deleteSession(String sessionId) { store.delete(sessionId); }

    private GameSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) { throw new TextGameNotFoundException("存档不存在"); }
        return store.find(sessionId).filter(session -> !session.expiresAt().isBefore(Instant.now()))
                .orElseThrow(() -> new TextGameNotFoundException("存档不存在或已过期"));
    }

    private TextGameSessionStore.StoryVersion requireVersion(long id) {
        return store.findVersion(id).orElseThrow(() -> new IllegalStateException("存档绑定的剧情版本不存在"));
    }

    private Instant nextExpiry() { return Instant.now().plus(properties.getSessionRetentionDays(), ChronoUnit.DAYS); }

    private GameState initialState(StoryDocument story) {
        GameState state = new GameState();
        JsonNode initial = story.initialState();
        state.setAttributes(objectMapper.convertValue(initial.path("attributes"), INT_MAP));
        state.setRelations(objectMapper.convertValue(initial.path("relations"), INT_MAP));
        state.setFlags(objectMapper.convertValue(initial.path("flags"), OBJECT_MAP));
        return state;
    }

}
