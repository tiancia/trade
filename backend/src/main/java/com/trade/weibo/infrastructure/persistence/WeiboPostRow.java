package com.trade.weibo.infrastructure.persistence;

import lombok.Data;
import lombok.experimental.Accessors;
import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class WeiboPostRow {
    private String id;
    private String targetUid;
    private String eventKey;
    private String eventJson;
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
    private String weiboId;
    private String lastError;
}
