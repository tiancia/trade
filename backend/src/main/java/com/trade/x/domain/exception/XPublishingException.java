package com.trade.x.domain.exception;

/** Raised only before a create-post request is sent. */
public class XPublishingException extends RuntimeException {
    private final Integer statusCode;

    public XPublishingException(String message) {
        super(message);
        this.statusCode = null;
    }

    public XPublishingException(String message, int statusCode) {
        super(message);
        if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("Invalid X HTTP status");
        this.statusCode = statusCode;
    }

    public Integer statusCode() { return statusCode; }
}
