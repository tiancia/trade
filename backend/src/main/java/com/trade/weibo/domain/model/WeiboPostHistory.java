package com.trade.weibo.domain.model;

import java.time.Instant;

public record WeiboPostHistory(long revision, int contentVersion, WeiboPostStatus status, String body,
                                String reviewer, String reviewReason, String attemptId, String weiboId,
                                String lastError, Instant updatedAt) {
}
