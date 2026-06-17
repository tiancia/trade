package com.trade.client.weibo;

public final class WeiboEndpoints {
    public static final String DEFAULT_OAUTH_BASE_URL = "https://api.weibo.com/oauth2";
    public static final String DEFAULT_API_BASE_URL = "https://api.weibo.com/2";

    public static final String AUTHORIZE = "/authorize";
    public static final String ACCESS_TOKEN = "/access_token";
    public static final String GET_UID = "/account/get_uid.json";
    public static final String STATUS_UPDATE = "/statuses/update.json";

    private WeiboEndpoints() {
    }
}
