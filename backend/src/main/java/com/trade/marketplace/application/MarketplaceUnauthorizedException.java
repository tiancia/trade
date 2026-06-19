package com.trade.marketplace.application;

public class MarketplaceUnauthorizedException extends RuntimeException {
    public MarketplaceUnauthorizedException(String message) {
        super(message);
    }
}
