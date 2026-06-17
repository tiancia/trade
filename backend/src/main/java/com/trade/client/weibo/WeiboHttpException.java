package com.trade.client.weibo;

public class WeiboHttpException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public WeiboHttpException(String method, String uri, int statusCode, String responseBody) {
        super("Weibo HTTP error, method=" + method + ", uri=" + uri
                + ", status=" + statusCode + ", body=" + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
