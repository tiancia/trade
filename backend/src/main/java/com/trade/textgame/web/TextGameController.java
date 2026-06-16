package com.trade.textgame.web;

import com.trade.textgame.application.TextGameConflictException;
import com.trade.textgame.application.TextGameNotFoundException;
import com.trade.textgame.application.TextGameSessionService;
import com.trade.textgame.model.TextGameApi;
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
    private final TextGameSessionService service;

    public TextGameController(TextGameSessionService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    public TextGameApi.Catalog catalog() {
        return service.catalog();
    }

    @PostMapping("/sessions")
    public TextGameApi.Session create(@RequestBody TextGameApi.CreateSessionRequest request) {
        return service.createSession(request);
    }

    @GetMapping("/sessions/{sessionId}")
    public TextGameApi.Session get(@PathVariable String sessionId) {
        return service.getSession(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/choices")
    public TextGameApi.Session choose(
            @PathVariable String sessionId,
            @RequestBody TextGameApi.SubmitChoiceRequest request
    ) {
        return service.submitChoice(sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/continue")
    public TextGameApi.Session continueGame(
            @PathVariable String sessionId,
            @RequestBody TextGameApi.ContinueRequest request
    ) {
        return service.continueGame(sessionId, request);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        service.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TextGameNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(TextGameNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TextGameConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(TextGameConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
