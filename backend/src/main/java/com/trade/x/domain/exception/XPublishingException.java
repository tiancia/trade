package com.trade.x.domain.exception;

/** Raised only before a create-post request is sent. */
public class XPublishingException extends RuntimeException {
    public XPublishingException(String message) { super(message); }
}
