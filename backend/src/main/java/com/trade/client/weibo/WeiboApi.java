package com.trade.client.weibo;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

public class WeiboApi {
    private final WeiboHttpClient client;
    private final WeiboClientProperties properties;

    public WeiboApi(WeiboHttpClient client) {
        this.client = client;
        this.properties = client.properties();
    }

    public String buildAuthorizeUrl(String state) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("client_id", properties.requiredClientId());
        params.put("response_type", "code");
        params.put("redirect_uri", properties.requiredRedirectUri());
        params.put("state", requiredText(state, "state is required"));
        return properties.normalizedOauthBaseUrl()
                + WeiboEndpoints.AUTHORIZE
                + "?"
                + WeiboHttpClient.formEncode(params);
    }

    public WeiboAccessToken exchangeCode(String code) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("client_id", properties.requiredClientId());
        params.put("client_secret", properties.requiredClientSecret());
        params.put("grant_type", "authorization_code");
        params.put("code", requiredText(code, "code is required"));
        params.put("redirect_uri", properties.requiredRedirectUri());
        JsonNode response = client.postFormJson(
                properties.normalizedOauthBaseUrl(),
                WeiboEndpoints.ACCESS_TOKEN,
                params
        );
        return new WeiboAccessToken(
                requiredJsonText(response, "access_token"),
                response.path("expires_in").asLong()
        );
    }

    public String getUid(String accessToken) {
        JsonNode response = client.getJson(
                properties.normalizedApiBaseUrl(),
                WeiboEndpoints.GET_UID,
                Map.of("access_token", requiredText(accessToken, "accessToken is required"))
        );
        return requiredJsonText(response, "uid");
    }

    public WeiboPublishResult publishText(String accessToken, String status) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("access_token", requiredText(accessToken, "accessToken is required"));
        params.put("status", requiredText(status, "status is required"));
        JsonNode response = client.postFormJson(
                properties.normalizedApiBaseUrl(),
                WeiboEndpoints.STATUS_UPDATE,
                params
        );
        return new WeiboPublishResult(
                optionalJsonText(response, "id"),
                optionalJsonText(response, "mid"),
                optionalJsonText(response, "created_at"),
                response
        );
    }

    private static String requiredJsonText(JsonNode node, String fieldName) {
        String value = optionalJsonText(node, fieldName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Weibo response missing field: " + fieldName);
        }
        return value;
    }

    private static String optionalJsonText(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.path(fieldName);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
