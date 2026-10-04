package com.trade.client.telegram;

public final class TelegramApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final Integer errorCode;
    private final String description;
    private final Integer retryAfterSeconds;
    private final Long migrateToChatId;

    public TelegramApiException(int statusCode, Integer errorCode, String description,
                                Integer retryAfterSeconds, Long migrateToChatId) {
        super("Telegram API request failed: status=" + statusCode
                + (errorCode == null ? "" : ", errorCode=" + errorCode));
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.description = description;
        this.retryAfterSeconds = retryAfterSeconds;
        this.migrateToChatId = migrateToChatId;
    }

    public int statusCode() {
        return statusCode;
    }

    public Integer errorCode() {
        return errorCode;
    }

    public String description() {
        return description;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Long migrateToChatId() {
        return migrateToChatId;
    }
}
