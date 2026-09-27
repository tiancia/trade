package com.trade.marketplace.domain.exception;

public class MarketplaceUnauthorizedException extends RuntimeException {
    public MarketplaceUnauthorizedException(String message) {
        super(message);
    }
}
