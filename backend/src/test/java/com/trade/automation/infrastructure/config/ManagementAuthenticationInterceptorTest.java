package com.trade.automation.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ManagementAuthenticationInterceptorTest {
    @TempDir Path directory;
    private ManagementAuthenticationProperties properties;
    private ManagementAuthenticationInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        Path credentials = directory.resolve("operators.htpasswd");
        Files.writeString(credentials, "operator:" + new BCryptPasswordEncoder(4).encode("test-password") + "\n");
        properties = new ManagementAuthenticationProperties();
        properties.setCredentialsFile(credentials.toString());
        interceptor = new ManagementAuthenticationInterceptor(properties);
    }

    @Test
    void readsAndWritesRequireApplicationCredentialsEvenIfGatewayIdentityIsForged() throws Exception {
        for (String method : new String[]{"GET", "HEAD", "POST", "PUT", "DELETE"}) {
            MockHttpServletRequest request = request(method);
            request.addHeader("X-Trade-CSRF", "1");
            request.addHeader("X-Authenticated-User", "operator");
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, response, new Object()));
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    void validCredentialsAreVerifiedAndCachedWithoutStoringRawAuthorization() throws Exception {
        for (int i = 0; i < 40; i++) {
            MockHttpServletRequest request = request("POST");
            request.addHeader("Authorization", basic("operator", "test-password"));
            assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        }
    }

    @Test
    void invalidPasswordsUsersAndEncodingsAreDenied() throws Exception {
        for (String header : new String[]{basic("operator", "wrong"), basic("someone", "test-password"),
                "Basic !!!!", "Basic ", "Bearer test-password", basic("operator", "test-password:")}) {
            MockHttpServletRequest request = request("GET");
            request.addHeader("Authorization", header);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request, response, new Object()));
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    void boundedVerificationBudgetLimitsBruteForceButCachedOperatorsCanContinue() throws Exception {
        MockHttpServletRequest operator = request("GET");
        operator.addHeader("Authorization", basic("operator", "test-password"));
        assertTrue(interceptor.preHandle(operator, new MockHttpServletResponse(), new Object()));
        MockHttpServletRequest invalid = request("GET");
        invalid.addHeader("Authorization", basic("operator", "wrong"));
        MockHttpServletResponse response = null;
        for (int i = 0; i < 31; i++) {
            response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(invalid, response, new Object()));
        }
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        assertTrue(interceptor.preHandle(operator, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void absentConfigurationFailsClosedAndInvalidFilesFailStartup() throws Exception {
        properties.setCredentialsFile("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(new ManagementAuthenticationInterceptor(properties).preHandle(request("GET"), response, new Object()));
        assertEquals(503, response.getStatus());
        Path invalid = directory.resolve("invalid");
        Files.writeString(invalid, "operator:plaintext\n");
        properties.setCredentialsFile(invalid.toString());
        assertThrows(IllegalArgumentException.class, () -> new ManagementAuthenticationInterceptor(properties));
        properties.setCredentialsFile(directory.resolve("missing").toString());
        assertThrows(IllegalArgumentException.class, () -> new ManagementAuthenticationInterceptor(properties));
    }

    @Test
    void onlyPreflightAndStateAuthenticatedOAuthCallbackAreExempt() throws Exception {
        MockHttpServletRequest preflight = request("OPTIONS");
        preflight.addHeader("Origin", "https://cockpit.example");
        preflight.addHeader("Access-Control-Request-Method", "POST");
        assertTrue(interceptor.preHandle(preflight, new MockHttpServletResponse(), new Object()));
        assertFalse(interceptor.preHandle(request("OPTIONS"), new MockHttpServletResponse(), new Object()));
        MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/api/weibo/oauth/callback");
        callback.setServletPath("/api/weibo/oauth/callback");
        assertTrue(interceptor.preHandle(callback, new MockHttpServletResponse(), new Object()));
        callback.setMethod("POST");
        assertFalse(interceptor.preHandle(callback, new MockHttpServletResponse(), new Object()));
    }

    private static MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/api/automation/tasks");
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
