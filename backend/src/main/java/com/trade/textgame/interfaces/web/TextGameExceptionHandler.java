package com.trade.textgame.interfaces.web;

import com.trade.textgame.domain.exception.TextGameConflictException;
import com.trade.textgame.domain.exception.TextGameNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackageClasses = TextGameController.class)
public class TextGameExceptionHandler {
    @ExceptionHandler(TextGameNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(TextGameNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(TextGameConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(TextGameConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
