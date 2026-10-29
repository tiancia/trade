package com.trade.textgame.interfaces.web;

import com.trade.textgame.application.service.TextGameSessionService;
import com.trade.textgame.domain.model.TextGameApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Player-facing HTTP API for the text game.
 *
 * <p>The API exposes catalog lookup plus session lifecycle operations. It does
 * not edit story definitions; admin-only story management lives in
 * {@link TextGameAdminController}.</p>
 */
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
}
