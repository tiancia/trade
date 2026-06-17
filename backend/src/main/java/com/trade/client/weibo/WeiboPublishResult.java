package com.trade.client.weibo;

import com.fasterxml.jackson.databind.JsonNode;

public record WeiboPublishResult(
        String id,
        String mid,
        String createdAt,
        JsonNode rawResponse
) {
}
