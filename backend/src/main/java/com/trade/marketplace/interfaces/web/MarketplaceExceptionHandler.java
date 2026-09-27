package com.trade.marketplace.interfaces.web;

import com.trade.marketplace.domain.exception.MarketplaceConflictException;
import com.trade.marketplace.domain.exception.MarketplaceForbiddenException;
import com.trade.marketplace.domain.exception.MarketplaceNotFoundException;
import com.trade.marketplace.domain.exception.MarketplaceUnauthorizedException;
import com.trade.marketplace.domain.exception.MarketplaceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps marketplace use-case failures to the public HTTP error contract.
 */
@RestControllerAdvice(basePackages = "com.trade.marketplace.interfaces.web")
public class MarketplaceExceptionHandler {
    @ExceptionHandler(MarketplaceUnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(MarketplaceUnauthorizedException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(MarketplaceForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(MarketplaceForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(MarketplaceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(MarketplaceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MarketplaceConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(MarketplaceConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(MarketplaceUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(MarketplaceUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
