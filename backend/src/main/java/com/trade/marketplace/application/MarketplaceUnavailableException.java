package com.trade.marketplace.application;

public class MarketplaceUnavailableException extends RuntimeException {
    public MarketplaceUnavailableException(String message) {
        super(message);
    }

    public MarketplaceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
