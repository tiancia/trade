package com.trade.marketplace.domain.exception;

public class MarketplaceNotFoundException extends RuntimeException {
    public MarketplaceNotFoundException(String message) {
        super(message);
    }
}
