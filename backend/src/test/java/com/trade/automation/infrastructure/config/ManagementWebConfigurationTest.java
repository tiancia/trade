package com.trade.automation.infrastructure.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManagementWebConfigurationTest {
    private static final String[] MANAGEMENT_PATHS = {
            "/api/automation/tasks/sample/start", "/api/automation/modules/story", "/api/trading/safety/resume", "/api/x/posts",
            "/api/weibo/posts", "/api/text-game/admin/stories/sample/versions"
    };
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ProbeController controller;
    @TempDir Path directory;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "trade.trading.frontend-allowed-origin-patterns", "https://cockpit.example,http://localhost:*",
                "trade.management.auth.enabled", "false"
        )));
        context.register(ManagementWebConfiguration.class, TestWebConfiguration.class);
        context.refresh();
        controller = context.getBean(ProbeController.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new ForwardedHeaderFilter()).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void crossSiteFormsCannotChangeAnyManagementModule() throws Exception {
        for (String path : MANAGEMENT_PATHS) {
            mvc.perform(post(path).header("Origin", "https://attacker.example")
                            .contentType("application/x-www-form-urlencoded"))
                    .andExpect(status().isForbidden());
        }
        assertEquals(0, controller.writes.get());
    }

    @Test
    void originlessOrSameOriginSimpleRequestsStillRequireTheHeader() throws Exception {
        mvc.perform(post(MANAGEMENT_PATHS[0])).andExpect(status().isForbidden());
        mvc.perform(post(MANAGEMENT_PATHS[0]).header("Origin", "http://localhost"))
                .andExpect(status().isForbidden());
        assertEquals(0, controller.writes.get());
    }

    @Test
    void anUntrustedOrOpaqueOriginIsRejectedEvenWithTheHeader() throws Exception {
        for (String origin : new String[]{"https://attacker.example", "null", ""}) {
            mvc.perform(post(MANAGEMENT_PATHS[0]).header("X-Trade-CSRF", "1").header("Origin", origin))
                    .andExpect(status().isForbidden());
        }
        assertEquals(0, controller.writes.get());
    }

    @Test
    void allManagementWriteMethodsAcceptTrustedOriginsAndTheHeader() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            for (String path : MANAGEMENT_PATHS) {
                mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
                                .header("Origin", "https://cockpit.example").header("X-Trade-CSRF", "1"))
                        .andExpect(status().isOk());
            }
        }
        assertEquals(MANAGEMENT_PATHS.length * 4, controller.writes.get());
    }

    @Test
    void deploymentForwardedOriginAndConfiguredDevelopmentPortsAreAccepted() throws Exception {
        mvc.perform(post(MANAGEMENT_PATHS[0]).header("X-Trade-CSRF", "1")
                        .header("Origin", "https://another-dashboard.example")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "another-dashboard.example"))
                .andExpect(status().isOk());
        mvc.perform(post(MANAGEMENT_PATHS[0]).header("X-Trade-CSRF", "1")
                        .header("Origin", "http://localhost:4173"))
                .andExpect(status().isOk());
        assertEquals(2, controller.writes.get());
    }

    @Test
    void commandLineClientsCanWriteWithoutOriginUsingTheHeader() throws Exception {
        mvc.perform(post(MANAGEMENT_PATHS[0]).header("X-Trade-CSRF", "1"))
                .andExpect(status().isOk());
        mvc.perform(post(MANAGEMENT_PATHS[0]).header("X-Trade-CSRF", "0"))
                .andExpect(status().isForbidden());
        assertEquals(1, controller.writes.get());
    }

    @Test
    void readOnlyAndPlayerRequestsKeepTheirExistingContract() throws Exception {
        mvc.perform(get("/api/automation/tasks")).andExpect(status().isOk());
        mvc.perform(get("/api/weibo/oauth/callback")).andExpect(status().isOk());
        mvc.perform(post("/api/text-game/sessions")).andExpect(status().isOk());
        mvc.perform(post("/api/marketplace/items")).andExpect(status().isOk());
        assertEquals(2, controller.writes.get());
    }

    @Test
    void applicationAuthenticationCoversEveryManagementRouteBeforeCsrfAndKeepsPlayerAndOAuthRoutes() throws Exception {
        context.close();
        Path file = directory.resolve("operators");
        Files.writeString(file, "operator:" + new BCryptPasswordEncoder(4).encode("test-password") + "\n");
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("auth", Map.of(
                "trade.management.auth.credentials-file", file.toString())));
        context.register(ManagementWebConfiguration.class, TestWebConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String authorization = "Basic " + Base64.getEncoder().encodeToString("operator:test-password".getBytes(StandardCharsets.UTF_8));
        for (String path : MANAGEMENT_PATHS) {
            mvc.perform(post(path).header("X-Trade-CSRF", "1")).andExpect(status().isUnauthorized());
            mvc.perform(post(path).header("Authorization", authorization)).andExpect(status().isForbidden());
            mvc.perform(post(path).header("Authorization", authorization).header("X-Trade-CSRF", "1"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/automation/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/automation/tasks").header("Authorization", authorization)).andExpect(status().isOk());
        mvc.perform(get("/api/weibo/oauth/callback")).andExpect(status().isOk());
        mvc.perform(post("/api/text-game/sessions")).andExpect(status().isOk());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestWebConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        final AtomicInteger writes = new AtomicInteger();

        @RequestMapping(value = {
                "/api/automation/tasks/sample/start", "/api/automation/modules/story", "/api/trading/safety/resume", "/api/x/posts",
                "/api/weibo/posts", "/api/text-game/admin/stories/sample/versions",
                "/api/text-game/sessions", "/api/marketplace/items"
        }, method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
        String write() {
            writes.incrementAndGet();
            return "ok";
        }

        @GetMapping({"/api/automation/tasks", "/api/weibo/oauth/callback"})
        String read() {
            return "ok";
        }
    }
}
