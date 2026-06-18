package com.trade.weibo.web;

import com.trade.client.weibo.WeiboClientProperties;
import com.trade.client.weibo.WeiboHttpException;
import com.trade.client.weibo.WeiboPublishResult;
import com.trade.weibo.WeiboAccount;
import com.trade.weibo.WeiboAccountService;
import com.trade.weibo.WeiboAuthorization;
import com.trade.weibo.WeiboAuthorizeUrl;
import com.trade.weibo.WeiboOAuthException;
import com.trade.weibo.WeiboOAuthService;
import com.trade.weibo.WeiboPublishingException;
import com.trade.weibo.WeiboPublishingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

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
    private final byte[] adminToken;

    public WeiboController(
            WeiboOAuthService oauthService,
            WeiboAccountService accountService,
            WeiboPublishingService publishingService,
            WeiboClientProperties properties
    ) {
        this.oauthService = oauthService;
        this.accountService = accountService;
        this.publishingService = publishingService;
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
                "status", e.statusCode(),
                "body", e.responseBody()
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
}
