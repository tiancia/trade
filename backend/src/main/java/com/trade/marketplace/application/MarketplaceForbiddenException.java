package com.trade.marketplace.application;

public class MarketplaceForbiddenException extends RuntimeException {
    public MarketplaceForbiddenException(String message) {
        super(message);
    }
}
