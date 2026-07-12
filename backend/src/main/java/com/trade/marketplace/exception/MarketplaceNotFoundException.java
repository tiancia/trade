package com.trade.marketplace.exception;

public class MarketplaceNotFoundException extends RuntimeException {
    public MarketplaceNotFoundException(String message) {
        super(message);
    }
}
