package com.trade.textgame.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class TextGameSessionRow {
    private String sessionId;
    private Long storyVersionId;
    private String currentNodeId;
    private String pendingNodeId;
    private String phase;
    private String attributesJson;
    private String relationsJson;
    private String flagsJson;
    private String historyJson;
    private String resultJson;
    private long revision;
    private Timestamp expiresAt;
    private Timestamp completedAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
