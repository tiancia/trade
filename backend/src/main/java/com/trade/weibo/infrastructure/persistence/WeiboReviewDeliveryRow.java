package com.trade.weibo.infrastructure.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class WeiboReviewDeliveryRow {
    private String id;
    private long botId;
    private String postId;
    private int version;
    private long chatId;
    private Long messageId;
    private String status;
    private Timestamp expiresAt;
    private String callbackId;
    private Boolean approved;
    private String reviewer;
    private Timestamp decidedAt;
    private String decisionStatus;
}
