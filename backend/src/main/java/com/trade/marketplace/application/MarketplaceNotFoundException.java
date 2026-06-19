package com.trade.marketplace.application;

public class MarketplaceNotFoundException extends RuntimeException {
    public MarketplaceNotFoundException(String message) {
        super(message);
    }
}
