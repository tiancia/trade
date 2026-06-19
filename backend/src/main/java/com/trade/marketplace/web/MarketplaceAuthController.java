package com.trade.marketplace.web;

import com.trade.marketplace.application.MarketplaceAuthService;
import com.trade.marketplace.model.MarketplaceApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/marketplace/auth")
public class MarketplaceAuthController {
    private final MarketplaceAuthService authService;

    public MarketplaceAuthController(MarketplaceAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public MarketplaceApi.AuthResponse register(@RequestBody MarketplaceApi.RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public MarketplaceApi.AuthResponse login(@RequestBody MarketplaceApi.LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        authService.logout(authorization);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MarketplaceApi.User me(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return authService.me(authorization);
    }
}
