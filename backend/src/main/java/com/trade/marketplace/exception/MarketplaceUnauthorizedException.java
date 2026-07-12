package com.trade.marketplace.exception;

public class MarketplaceUnauthorizedException extends RuntimeException {
    public MarketplaceUnauthorizedException(String message) {
        super(message);
    }
}
