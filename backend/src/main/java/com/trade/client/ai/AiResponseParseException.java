package com.trade.client.ai;

public class AiResponseParseException extends RuntimeException {
    private final String rawResponse;

    public AiResponseParseException(String message, String rawResponse) {
        super(message);
        this.rawResponse = rawResponse;
    }

    public AiResponseParseException(String message, String rawResponse, Throwable cause) {
        super(message, cause);
        this.rawResponse = rawResponse;
    }

    public String getRawResponse() {
        return rawResponse;
    }
}
