package com.trade.x.interfaces.web;

import com.trade.x.application.service.XPostService;
import com.trade.x.domain.model.XPost;
import com.trade.x.domain.model.XPostHistory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Draft management behind the deployment gateway; approval uses authenticated Telegram callbacks. */
@RestController
@RequestMapping("/api/x/posts")
public class XPostController {
    private final XPostService posts;

    public XPostController(XPostService posts) {
        this.posts = posts;
    }

    @PostMapping
    public ResponseEntity<XPost> generate() {
        return posts.generate().map(post -> ResponseEntity.status(HttpStatus.CREATED).body(post))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @GetMapping
    public List<XPost> recent(@RequestParam(defaultValue = "30") int limit) { return posts.recent(limit); }

    @GetMapping("/{id}")
    public XPost get(@PathVariable String id) {
        return posts.get(id);
    }

    @GetMapping("/{id}/history")
    public List<XPostHistory> history(@PathVariable String id) {
        return posts.history(id);
    }

    @PutMapping("/{id}")
    public XPost revise(@PathVariable String id,
            @RequestBody ReviseRequest request) {
        if (request == null) throw new IllegalArgumentException("X draft input is required");
        return posts.revise(id, request.expectedRevision(), request.body());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", failure.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", failure.getMessage()));
    }

    public record ReviseRequest(long expectedRevision, String body) { }
}
