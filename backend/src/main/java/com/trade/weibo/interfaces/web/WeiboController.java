package com.trade.weibo.interfaces.web;

import com.trade.client.weibo.WeiboClientProperties;
import com.trade.client.weibo.WeiboHttpException;
import com.trade.client.weibo.WeiboPublishResult;
import com.trade.weibo.application.service.WeiboAccountService;
import com.trade.weibo.application.service.WeiboOAuthService;
import com.trade.weibo.application.service.WeiboPublishingService;
import com.trade.weibo.application.service.WeiboPostService;
import com.trade.weibo.domain.model.HotEvent;
import com.trade.weibo.domain.model.WeiboPost;
import com.trade.weibo.domain.model.WeiboPostHistory;
import com.trade.weibo.domain.exception.WeiboOAuthException;
import com.trade.weibo.domain.exception.WeiboPublishingException;
import com.trade.weibo.domain.model.WeiboAccount;
import com.trade.weibo.domain.model.WeiboAuthorization;
import com.trade.weibo.domain.model.WeiboAuthorizeUrl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.List;
import java.time.Instant;

/**
 * Admin-protected Weibo OAuth and publishing API.
 *
 * <p>The controller is absent unless {@code trade.weibo.admin-token} is set.
 * Most endpoints require {@code X-Weibo-Admin-Token}; the OAuth callback stays
 * open because Weibo redirects back without that custom header.</p>
 */
@RestController
@RequestMapping("/api/weibo")
@ConditionalOnExpression("'${trade.weibo.admin-token:}' != ''")
public class WeiboController {
    private static final String ADMIN_TOKEN_HEADER = "X-Weibo-Admin-Token";

    private final WeiboOAuthService oauthService;
    private final WeiboAccountService accountService;
    private final WeiboPublishingService publishingService;
    private final WeiboPostService postService;
    private final byte[] adminToken;

    public WeiboController(
            WeiboOAuthService oauthService,
            WeiboAccountService accountService,
            WeiboPublishingService publishingService,
            WeiboPostService postService,
            WeiboClientProperties properties
    ) {
        this.oauthService = oauthService;
        this.accountService = accountService;
        this.publishingService = publishingService;
        this.postService = postService;
        this.adminToken = properties.requiredAdminToken().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/oauth/authorize-url")
    public WeiboAuthorizeUrl authorizeUrl(
            @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String supplied
    ) {
        authorize(supplied);
        return oauthService.createAuthorizeUrl();
    }

    @GetMapping("/oauth/callback")
    public WeiboAuthorization callback(@RequestParam String code, @RequestParam String state) {
        return oauthService.handleCallback(code, state);
    }

    @GetMapping("/account")
    public WeiboAccount account(
            @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String supplied
    ) {
        authorize(supplied);
        return accountService.currentAccount();
    }

    @PostMapping("/statuses")
    public WeiboPublishResult publish(
            @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String supplied,
            @RequestBody PublishTextRequest request
    ) {
        authorize(supplied);
        return publishingService.publishText(request == null ? null : request.status());
    }

    @PostMapping("/posts")
    public ResponseEntity<WeiboPost> generatePost(
            @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String supplied,
            @RequestBody GeneratePostRequest request) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("Event input is required");
        HotEvent event = new HotEvent(request.title(), request.sourceUrl(), request.summary(),
                request.occurredAt(), Instant.now());
        return postService.generate(event).map(post -> ResponseEntity.status(HttpStatus.CREATED).body(post))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @GetMapping("/posts")
    public List<WeiboPost> posts(@RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String supplied,
                                @RequestParam(defaultValue = "30") int limit) {
        authorize(supplied);
        return postService.recent(limit);
    }

    @GetMapping("/posts/{id}")
    public WeiboPost post(@RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String supplied,
                           @PathVariable String id) {
        authorize(supplied);
        return postService.get(id);
    }

    @GetMapping("/posts/{id}/history")
    public List<WeiboPostHistory> history(
            @RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String supplied, @PathVariable String id) {
        authorize(supplied);
        return postService.history(id);
    }

    @PutMapping("/posts/{id}")
    public WeiboPost revise(@RequestHeader(value = ADMIN_TOKEN_HEADER, required = false) String supplied,
                             @PathVariable String id, @RequestBody RevisePostRequest request) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("Post input is required");
        return postService.revise(id, request.expectedRevision(), request.body());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WeiboUnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(WeiboUnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, WeiboOAuthException.class, WeiboPublishingException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WeiboHttpException.class)
    public ResponseEntity<Map<String, Object>> weiboHttpError(WeiboHttpException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "error", "Weibo API request failed",
                "status", e.statusCode()
        ));
    }

    private void authorize(String supplied) {
        byte[] candidate = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(adminToken, candidate)) {
            throw new WeiboUnauthorizedException("Weibo admin token is invalid");
        }
    }

    public record PublishTextRequest(String status) {
    }

    public record GeneratePostRequest(String title, String sourceUrl, String summary, Instant occurredAt) { }
    public record RevisePostRequest(long expectedRevision, String body) { }
}
