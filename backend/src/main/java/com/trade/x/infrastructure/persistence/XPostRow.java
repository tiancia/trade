package com.trade.x.infrastructure.persistence;

import lombok.Data;
import lombok.experimental.Accessors;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class XPostRow {
    private String id;
    private String targetUserId;
    private String generationKey;
    private String contentPolicyJson;
    private String body;
    private String reviewNote;
    private int contentVersion;
    private long revision;
    private String status;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Timestamp expiresAt;
    private String reviewer;
    private Timestamp reviewedAt;
    private String reviewReason;
    private String attemptId;
    private Timestamp publishStartedAt;
    private String postId;
    private String lastError;
}
