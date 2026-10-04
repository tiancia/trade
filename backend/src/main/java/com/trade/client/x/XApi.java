package com.trade.client.x;

import com.fasterxml.jackson.databind.JsonNode;
import com.trade.client.x.dto.XPost;
import com.trade.client.x.dto.XUser;

import java.util.Objects;

public class XApi {
    private final XHttpClient client;

    public XApi(XHttpClient client) {
        this.client = Objects.requireNonNull(client, "X HTTP client is required");
    }

    public XUser getMe() {
        JsonNode data = client.getMe();
        requireId(data);
        return client.readData(data, XUser.class);
    }

    public XPost publishText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("X post text is required");
        JsonNode data = client.publishText(text);
        requireId(data);
        return client.readData(data, XPost.class);
    }

    private static void requireId(JsonNode data) {
        JsonNode id = data.get("id");
        if (id == null || !id.isTextual() || !id.textValue().matches("[1-9][0-9]{0,18}")) {
            throw new IllegalStateException("X response is missing a valid identifier");
        }
    }
}
