package com.trade.textgame.support;

import com.trade.textgame.persistence.TextGameMapper;
import com.trade.textgame.persistence.TextGameSessionEventRow;
import com.trade.textgame.persistence.TextGameSessionRow;
import com.trade.textgame.persistence.TextGameStoryRow;
import com.trade.textgame.persistence.TextGameVersionRow;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryTextGameMapper implements TextGameMapper {
    private final Map<Long, TextGameStoryRow> stories = new LinkedHashMap<>();
    private final Map<Long, TextGameVersionRow> versions = new LinkedHashMap<>();
    private final Map<String, TextGameSessionRow> sessions = new LinkedHashMap<>();
    private final List<TextGameSessionEventRow> events = new ArrayList<>();
    private long storySequence;
    private long versionSequence;
    private long eventSequence;

    @Override
    public List<TextGameVersionRow> findPublishedCatalog() {
        return versions.values().stream()
                .filter(v -> "PUBLISHED".equals(v.getStatus()))
                .filter(v -> stories.get(v.getStoryId()).isEnabled())
                .sorted(Comparator.comparingInt(v -> stories.get(v.getStoryId()).getSortOrder()))
                .map(this::enrich)
                .toList();
    }

    @Override
    public TextGameVersionRow findLatestPublished(String storyKey) {
        TextGameStoryRow story = findStoryByKey(storyKey);
        if (story == null || !story.isEnabled()) {
            return null;
        }
        return versions.values().stream()
                .filter(v -> v.getStoryId().equals(story.getId()) && "PUBLISHED".equals(v.getStatus()))
                .max(Comparator.comparingInt(TextGameVersionRow::getVersionNumber))
                .map(this::enrich)
                .orElse(null);
    }

    @Override
    public TextGameVersionRow findVersionById(long id) {
        TextGameVersionRow row = versions.get(id);
        return row == null ? null : enrich(row);
    }

    @Override
    public TextGameVersionRow findVersion(long storyId, int versionNumber) {
        return versions.values().stream()
                .filter(v -> v.getStoryId() == storyId && v.getVersionNumber() == versionNumber)
                .findFirst().map(this::enrich).orElse(null);
    }

    @Override
    public TextGameStoryRow findStoryByKey(String storyKey) {
        return stories.values().stream().filter(s -> storyKey.equals(s.getStoryKey())).findFirst().orElse(null);
    }

    @Override
    public List<TextGameStoryRow> listStories() {
        return new ArrayList<>(stories.values());
    }

    @Override
    public List<TextGameVersionRow> listVersions(long storyId) {
        return versions.values().stream().filter(v -> v.getStoryId() == storyId)
                .sorted(Comparator.comparingInt(TextGameVersionRow::getVersionNumber).reversed())
                .map(this::enrich).toList();
    }

    @Override
    public void insertStory(TextGameStoryRow row) {
        row.setId(++storySequence);
        stories.put(row.getId(), row);
    }

    @Override
    public void insertVersion(TextGameVersionRow row) {
        row.setId(++versionSequence);
        versions.put(row.getId(), row);
    }

    @Override
    public int updateDraft(long id, String storyJson, String checksum, long expectedRevision) {
        TextGameVersionRow row = versions.get(id);
        if (row == null || !"DRAFT".equals(row.getStatus()) || row.getRevision() != expectedRevision) {
            return 0;
        }
        row.setStoryJson(storyJson).setChecksum(checksum).setRevision(row.getRevision() + 1);
        return 1;
    }

    @Override
    public int archivePublished(long storyId) {
        int count = 0;
        for (TextGameVersionRow row : versions.values()) {
            if (row.getStoryId() == storyId && "PUBLISHED".equals(row.getStatus())) {
                row.setStatus("ARCHIVED");
                count++;
            }
        }
        return count;
    }

    @Override
    public int publishDraft(long id, long expectedRevision, Timestamp publishedAt) {
        TextGameVersionRow row = versions.get(id);
        if (row == null || !"DRAFT".equals(row.getStatus()) || row.getRevision() != expectedRevision) {
            return 0;
        }
        row.setStatus("PUBLISHED").setPublishedAt(publishedAt).setRevision(row.getRevision() + 1);
        return 1;
    }

    @Override
    public void updateStoryMetadata(TextGameStoryRow row) {
        stories.put(row.getId(), row);
    }

    @Override
    public TextGameSessionRow findSession(String sessionId) {
        TextGameSessionRow row = sessions.get(sessionId);
        return row == null ? null : copy(row);
    }

    @Override
    public void insertSession(TextGameSessionRow row) {
        sessions.put(row.getSessionId(), copy(row));
    }

    @Override
    public int updateSession(TextGameSessionRow row) {
        TextGameSessionRow stored = sessions.get(row.getSessionId());
        if (stored == null || stored.getRevision() != row.getRevision()) {
            return 0;
        }
        TextGameSessionRow next = copy(row).setRevision(row.getRevision() + 1);
        sessions.put(row.getSessionId(), next);
        return 1;
    }

    @Override
    public int deleteSession(String sessionId) {
        return sessions.remove(sessionId) == null ? 0 : 1;
    }

    @Override
    public void insertSessionEvent(TextGameSessionEventRow row) {
        row.setId(++eventSequence);
        events.add(row);
    }

    @Override
    public List<TextGameSessionEventRow> listSessionEvents(String sessionId) {
        return events.stream().filter(e -> sessionId.equals(e.getSessionId())).toList();
    }

    private TextGameVersionRow enrich(TextGameVersionRow row) {
        TextGameStoryRow story = stories.get(row.getStoryId());
        row.setStoryKey(story.getStoryKey()).setTitle(story.getTitle()).setSummary(story.getSummary())
                .setEnabled(story.isEnabled()).setSortOrder(story.getSortOrder());
        return row;
    }

    private static TextGameSessionRow copy(TextGameSessionRow row) {
        return new TextGameSessionRow()
                .setSessionId(row.getSessionId())
                .setStoryVersionId(row.getStoryVersionId())
                .setCurrentNodeId(row.getCurrentNodeId())
                .setPendingNodeId(row.getPendingNodeId())
                .setPhase(row.getPhase())
                .setAttributesJson(row.getAttributesJson())
                .setRelationsJson(row.getRelationsJson())
                .setFlagsJson(row.getFlagsJson())
                .setHistoryJson(row.getHistoryJson())
                .setResultJson(row.getResultJson())
                .setRevision(row.getRevision())
                .setExpiresAt(row.getExpiresAt())
                .setCompletedAt(row.getCompletedAt())
                .setCreatedAt(row.getCreatedAt())
                .setUpdatedAt(row.getUpdatedAt());
    }
}
