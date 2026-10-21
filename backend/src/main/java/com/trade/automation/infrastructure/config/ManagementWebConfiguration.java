package com.trade.automation.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Browser request protection for operational APIs already protected by the deployment gateway. */
@Configuration
public class ManagementWebConfiguration implements WebMvcConfigurer {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final CorsConfiguration allowedOrigins = new CorsConfiguration();

    public ManagementWebConfiguration(
            @Value("${trade.trading.frontend-allowed-origin-patterns:http://localhost:*}") List<String> originPatterns
    ) {
        allowedOrigins.setAllowedOriginPatterns(originPatterns);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws IOException {
                if (SAFE_METHODS.contains(request.getMethod())) {
                    return true;
                }
                // A fixed non-simple header prevents cross-site forms; it is not an authentication token.
                if (!"1".equals(request.getHeader("X-Trade-CSRF")) || !trustedOrigin(request)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Management writes require X-Trade-CSRF: 1 and a trusted Origin\"}");
                    return false;
                }
                return true;
            }
        }).addPathPatterns("/api/automation/**", "/api/trading/**", "/api/x/**", "/api/weibo/**",
                "/api/text-game/admin/**");
    }

    private boolean trustedOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        // CLI clients may omit Origin, but must still send the non-simple CSRF header.
        if (origin == null) {
            return true;
        }
        if (origin.isBlank() || "null".equals(origin)) {
            return false;
        }
        try {
            return !CorsUtils.isCorsRequest(request) || allowedOrigins.checkOrigin(origin) != null;
        } catch (IllegalArgumentException invalidOrigin) {
            return false;
        }
    }
}
