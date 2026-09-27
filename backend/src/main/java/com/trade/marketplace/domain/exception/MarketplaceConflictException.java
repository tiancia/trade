package com.trade.marketplace.domain.exception;

public class MarketplaceConflictException extends RuntimeException {
    public MarketplaceConflictException(String message) {
        super(message);
    }
}
