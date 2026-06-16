package com.trade.textgame.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.trade.textgame.application.TextGameAdminService;
import com.trade.textgame.application.TextGameConflictException;
import com.trade.textgame.application.TextGameNotFoundException;
import com.trade.textgame.config.TextGameProperties;
import com.trade.textgame.domain.StoryValidation;
import com.trade.textgame.model.TextGameAdminApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/text-game/admin")
@ConditionalOnExpression("'${trade.text-game.admin-token:}' != ''")
public class TextGameAdminController {
    private final TextGameAdminService service;
    private final byte[] token;

    public TextGameAdminController(TextGameAdminService service, TextGameProperties properties) {
        this.service = service;
        this.token = properties.getAdminToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/validate")
    public StoryValidation.Result validate(
            @RequestHeader("X-Admin-Token") String supplied,
            @RequestBody JsonNode story
    ) {
        authorize(supplied);
        return service.validate(story);
    }

    @GetMapping("/stories")
    public List<TextGameAdminApi.StoryView> stories(@RequestHeader("X-Admin-Token") String supplied) {
        authorize(supplied);
        return service.listStories();
    }

    @PostMapping("/stories/{storyKey}/versions")
    public TextGameAdminApi.VersionDocument createDraft(
            @RequestHeader("X-Admin-Token") String supplied,
            @PathVariable String storyKey,
            @RequestBody TextGameAdminApi.CreateDraftRequest request
    ) {
        authorize(supplied);
        return service.createDraft(storyKey, request);
    }

    @GetMapping("/stories/{storyKey}/versions/{versionNumber}")
    public TextGameAdminApi.VersionDocument getVersion(
            @RequestHeader("X-Admin-Token") String supplied,
            @PathVariable String storyKey,
            @PathVariable int versionNumber
    ) {
        authorize(supplied);
        return service.getVersion(storyKey, versionNumber);
    }

    @PutMapping("/stories/{storyKey}/versions/{versionNumber}")
    public TextGameAdminApi.VersionDocument replaceDraft(
            @RequestHeader("X-Admin-Token") String supplied,
            @PathVariable String storyKey,
            @PathVariable int versionNumber,
            @RequestBody TextGameAdminApi.ReplaceDraftRequest request
    ) {
        authorize(supplied);
        return service.replaceDraft(storyKey, versionNumber, request);
    }

    @PostMapping("/stories/{storyKey}/versions/{versionNumber}/publish")
    public TextGameAdminApi.VersionDocument publish(
            @RequestHeader("X-Admin-Token") String supplied,
            @PathVariable String storyKey,
            @PathVariable int versionNumber,
            @RequestBody TextGameAdminApi.PublishRequest request
    ) {
        authorize(supplied);
        return service.publish(storyKey, versionNumber, request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TextGameNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(TextGameNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TextGameConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(TextGameConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private void authorize(String supplied) {
        byte[] candidate = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(token, candidate)) {
            throw new UnauthorizedException("管理令牌无效");
        }
    }

    private static final class UnauthorizedException extends RuntimeException {
        private UnauthorizedException(String message) {
            super(message);
        }
    }
}
