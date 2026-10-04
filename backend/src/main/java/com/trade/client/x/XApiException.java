package com.trade.client.x;

public final class XApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int statusCode;

    public XApiException(int statusCode) {
        super("X API request failed: status=" + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
