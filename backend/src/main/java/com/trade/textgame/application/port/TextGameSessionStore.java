package com.trade.textgame.application.port;

import com.trade.textgame.domain.model.GameSession;
import com.trade.textgame.domain.model.StoryDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Typed session persistence; row layout and JSON encoding belong to the adapter. */
public interface TextGameSessionStore {
    record StoryVersion(long id, String storyKey, String title, String summary, int versionNumber, StoryDocument story) {}
    List<StoryVersion> publishedCatalog();
    Optional<StoryVersion> latestPublished(String storyKey);
    Optional<StoryVersion> findVersion(long id);
    Optional<GameSession> find(String sessionId);
    void insert(GameSession session);
    /** Compare the supplied revision and return the committed next revision. */
    GameSession update(GameSession session, Instant expiresAt);
    void recordChoice(GameSession session);
    void delete(String sessionId);
}
