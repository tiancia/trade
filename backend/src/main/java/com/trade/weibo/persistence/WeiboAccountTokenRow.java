package com.trade.weibo.persistence;

import lombok.Data;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

@Data
@Accessors(chain = true)
public class WeiboAccountTokenRow {
    private String uid;
    private String accessToken;
    private Timestamp expiresAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
