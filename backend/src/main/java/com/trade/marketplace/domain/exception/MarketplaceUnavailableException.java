package com.trade.marketplace.domain.exception;

public class MarketplaceUnavailableException extends RuntimeException {
    public MarketplaceUnavailableException(String message) {
        super(message);
    }

    public MarketplaceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
