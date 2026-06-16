package com.trade.textgame.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class TextGameAdminApi {
    private TextGameAdminApi() {
    }

    public record CreateDraftRequest(Integer versionNumber, JsonNode story) {
    }

    public record ReplaceDraftRequest(Long expectedRevision, JsonNode story) {
    }

    public record PublishRequest(Long expectedRevision) {
    }

    public record StoryView(
            String storyKey,
            String title,
            String summary,
            boolean enabled,
            int sortOrder,
            List<VersionSummary> versions
    ) {
    }

    public record VersionSummary(
            int versionNumber,
            String status,
            long revision,
            String checksum,
            Instant publishedAt
    ) {
    }

    public record VersionDocument(
            String storyKey,
            int versionNumber,
            String status,
            long revision,
            String checksum,
            JsonNode story
    ) {
    }
}
