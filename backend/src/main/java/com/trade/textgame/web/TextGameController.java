package com.trade.textgame.web;

import com.trade.textgame.application.TextGameAiException;
import com.trade.textgame.application.TextGameConflictException;
import com.trade.textgame.application.TextGameNotFoundException;
import com.trade.textgame.application.TextGameService;
import com.trade.textgame.model.CreateTextGameSessionRequest;
import com.trade.textgame.model.SubmitTextGameInterludeActionRequest;
import com.trade.textgame.model.SubmitTextGameChoiceRequest;
import com.trade.textgame.model.TextGameCatalogResponse;
import com.trade.textgame.model.TextGameSessionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/text-game")
public class TextGameController {
    private final TextGameService textGameService;

    public TextGameController(TextGameService textGameService) {
        this.textGameService = textGameService;
    }

    @GetMapping("/catalog")
    public TextGameCatalogResponse catalog() {
        return textGameService.catalog();
    }

    @PostMapping("/sessions")
    public TextGameSessionResponse createSession(
            @RequestBody(required = false) CreateTextGameSessionRequest request
    ) {
        return textGameService.createSession(request);
    }

    @GetMapping("/sessions/{sessionId}")
    public TextGameSessionResponse getSession(@PathVariable String sessionId) {
        return textGameService.getSession(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/choices")
    public TextGameSessionResponse submitChoice(
            @PathVariable String sessionId,
            @RequestBody SubmitTextGameChoiceRequest request
    ) {
        return textGameService.submitChoice(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/interlude-actions")
    public TextGameSessionResponse submitInterludeAction(
            @PathVariable String sessionId,
            @RequestBody SubmitTextGameInterludeActionRequest request
    ) {
        return textGameService.submitInterludeAction(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/resolution/retry")
    public TextGameSessionResponse retryResolution(@PathVariable String sessionId) {
        return textGameService.retryResolution(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/resolution/advance")
    public TextGameSessionResponse advanceResolution(@PathVariable String sessionId) {
        return textGameService.advanceResolution(sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        textGameService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TextGameNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TextGameNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TextGameConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(TextGameConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(TextGameAiException.class)
    public ResponseEntity<Map<String, String>> handleAiError(TextGameAiException e) {
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
