package com.trade.x.interfaces.web;

import com.trade.x.application.service.XPostService;
import com.trade.x.domain.model.XPost;
import com.trade.x.domain.model.XPostHistory;
import com.trade.x.infrastructure.config.XPublishingProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Admin draft management; approval is accepted exclusively from authenticated Telegram callbacks. */
@RestController
@RequestMapping("/api/x/posts")
@Conditional(XPostController.AdminTokenConfigured.class)
public class XPostController {
    private static final String HEADER = "X-X-Admin-Token";
    private final XPostService posts;
    private final byte[] adminToken;

    public XPostController(XPostService posts, XPublishingProperties settings) {
        this.posts = posts;
        this.adminToken = settings.requiredAdminToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<XPost> generate(@RequestHeader(value = HEADER, required = false) String token) {
        authorize(token);
        return posts.generate().map(post -> ResponseEntity.status(HttpStatus.CREATED).body(post))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @GetMapping
    public List<XPost> recent(@RequestHeader(value = HEADER, required = false) String token,
            @RequestParam(defaultValue = "30") int limit) { authorize(token); return posts.recent(limit); }

    @GetMapping("/{id}")
    public XPost get(@RequestHeader(value = HEADER, required = false) String token, @PathVariable String id) {
        authorize(token); return posts.get(id);
    }

    @GetMapping("/{id}/history")
    public List<XPostHistory> history(@RequestHeader(value = HEADER, required = false) String token, @PathVariable String id) {
        authorize(token); return posts.history(id);
    }

    @PutMapping("/{id}")
    public XPost revise(@RequestHeader(value = HEADER, required = false) String token, @PathVariable String id,
            @RequestBody ReviseRequest request) {
        authorize(token);
        if (request == null) throw new IllegalArgumentException("X draft input is required");
        return posts.revise(id, request.expectedRevision(), request.body());
    }

    @ExceptionHandler(XUnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(XUnauthorizedException failure) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", failure.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", failure.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", failure.getMessage()));
    }

    private void authorize(String supplied) {
        if (!MessageDigest.isEqual(adminToken, supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new XUnauthorizedException();
        }
    }
    public record ReviseRequest(long expectedRevision, String body) { }

    /** Avoid interpolating the secret into an expression or diagnostic message. */
    public static class AdminTokenConfigured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String token = context.getEnvironment().getProperty("trade.x.admin-token");
            return token != null && !token.isBlank();
        }
    }
}
