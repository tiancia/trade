package com.trade.textgame.domain.exception;

public class TextGameNotFoundException extends RuntimeException {
    public TextGameNotFoundException(String message) {
        super(message);
    }
}
