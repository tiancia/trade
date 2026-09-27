package com.trade.marketplace.domain.exception;

public class MarketplaceForbiddenException extends RuntimeException {
    public MarketplaceForbiddenException(String message) {
        super(message);
    }
}
