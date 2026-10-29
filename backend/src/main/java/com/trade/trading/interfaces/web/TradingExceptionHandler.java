package com.trade.trading.interfaces.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.ConcurrentModificationException;

/** Shared request-validation and optimistic-lock responses for trading APIs. */
@RestControllerAdvice(assignableTypes = {
        TradingController.class, StrategyConfigurationController.class, PaperAccountController.class,
        UnknownOrderRecoveryController.class, TradingMarketDataController.class
})
public class TradingExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, ConcurrentModificationException.class})
    public void handle(RuntimeException exception, HttpServletResponse response) throws IOException {
        // Spring also matches nested causes. Preserve an enclosing HTTP status or server failure.
        HttpStatus status = switch (exception) {
            case IllegalArgumentException ignored -> HttpStatus.BAD_REQUEST;
            case ConcurrentModificationException ignored -> HttpStatus.CONFLICT;
            default -> throw exception;
        };
        response.sendError(status.value(), exception.getMessage());
    }
}
