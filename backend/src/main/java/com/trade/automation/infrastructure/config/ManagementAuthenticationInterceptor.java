package com.trade.automation.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** No gateway identity header is trusted; passwords are verified by this process. */
final class ManagementAuthenticationInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ManagementAuthenticationInterceptor.class);
    private static final String PRINCIPAL = ManagementAuthenticationInterceptor.class.getName() + ".principal";
    private static final long CACHE_MILLIS = 30_000;
    private final ManagementAuthenticationProperties properties;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, String> credentials;
    private final Map<String, CachedPrincipal> cache = new LinkedHashMap<>();
    private long windowStart = System.nanoTime();
    private int verifications;

    ManagementAuthenticationInterceptor(ManagementAuthenticationProperties properties) {
        this.properties = properties;
        this.credentials = loadCredentials(properties);
    }

    private static Map<String, String> loadCredentials(ManagementAuthenticationProperties properties) {
        if (!properties.isEnabled() || properties.getCredentialsFile().isBlank()) {
            return Map.of();
        }
        try {
            Path file = Path.of(properties.getCredentialsFile());
            if (Files.size(file) > 65_536) {
                throw new IllegalArgumentException("Management credentials file is too large");
            }
            Map<String, String> result = new HashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] entry = line.split(":", 2);
                if (entry.length != 2 || !entry[0].matches("[A-Za-z0-9_.@-]{1,64}")
                        || !entry[1].matches("\\$2[aby]\\$(0[4-9]|1[0-4])\\$[./A-Za-z0-9]{53}")
                        || result.putIfAbsent(entry[0], entry[1]) != null) {
                    throw new IllegalArgumentException("Management credentials require unique users and bcrypt hashes (cost 4-14)");
                }
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Management credentials file is empty");
            return Map.copyOf(result);
        } catch (IOException invalidFile) {
            throw new IllegalArgumentException("Cannot read management credentials file", invalidFile);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // OAuth callback authenticates its own state; browser preflight never executes a controller write.
        if (!properties.isEnabled() || CorsUtils.isPreFlightRequest(request)
                || ("GET".equals(request.getMethod())
                && (request.getContextPath() + "/api/weibo/oauth/callback").equals(request.getRequestURI()))) {
            return true;
        }
        if (credentials.isEmpty()) {
            return deny(request, response, 503, "Management authentication is not configured");
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() > 1024 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Trade operator\", charset=\"UTF-8\"");
            return deny(request, response, 401, "Operator authentication required");
        }
        String digest = digest(header);
        String principal = cachedPrincipal(digest);
        if (principal == null) {
            if (!reserveVerification()) return deny(request, response, 429, "Authentication rate exceeded");
            principal = verify(header.substring(6));
            if (principal == null) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Trade operator\"");
                return deny(request, response, 401, "Invalid operator credentials");
            }
            cachePrincipal(digest, principal);
        }
        request.setAttribute(PRINCIPAL, principal);
        return true;
    }

    private String verify(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 1) return null;
            String user = decoded.substring(0, colon);
            String hash = credentials.get(user);
            // An unknown user still consumes one bcrypt operation and the same bounded budget.
            String candidate = hash == null ? credentials.values().iterator().next() : hash;
            boolean matches = encoder.matches(decoded.substring(colon + 1), candidate);
            return hash != null && matches ? user : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private synchronized boolean reserveVerification() {
        long now = System.nanoTime();
        if (now - windowStart >= 60_000_000_000L) {
            windowStart = now;
            verifications = 0;
        }
        return ++verifications <= 30;
    }

    private synchronized String cachedPrincipal(String digest) {
        CachedPrincipal entry = cache.get(digest);
        return entry != null && System.nanoTime() - entry.createdAt() < CACHE_MILLIS * 1_000_000L
                ? entry.principal() : null;
    }

    private synchronized void cachePrincipal(String digest, String principal) {
        cache.put(digest, new CachedPrincipal(principal, System.nanoTime()));
        if (cache.size() > 256) {
            Iterator<String> keys = cache.keySet().iterator();
            keys.next();
            keys.remove();
        }
    }

    private static String digest(String header) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(header.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean deny(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 429) response.setHeader(HttpHeaders.RETRY_AFTER, "60");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
        audit(request, status, "unauthenticated");
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        Object principal = request.getAttribute(PRINCIPAL);
        if (principal != null && (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())
                || response.getStatus() >= 400)) {
            audit(request, exception == null ? response.getStatus() : 500, principal.toString());
        }
    }

    private static void audit(HttpServletRequest request, int status, String principal) {
        // No query string, Authorization, request body or provider error is written to the audit log.
        log.info("management_audit operator={} method={} path={} status={}", principal,
                request.getMethod(), request.getRequestURI().replaceAll("[\\r\\n\\t]", "_"), status);
    }

    private record CachedPrincipal(String principal, long createdAt) { }
}
