package com.trade.x.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class XWorkflowPolicyTest {
    @Test
    void disabledWorkflowAcceptsEmptyTargetAndDirectionAndRejectsWork() {
        var policy = workflow(false, false, false, "", "");
        assertThrows(IllegalStateException.class, policy::requireEnabled);
        assertThrows(IllegalStateException.class, policy::requireGeneration);
        assertThrows(IllegalStateException.class, policy::requirePublishing);
    }

    @Test
    void generationRequiresDirectionButPublishingDoesNotRequireNewGenerationSettings() {
        var generation = workflow(true, true, false, "42", "engineering");
        assertDoesNotThrow(generation::requireGeneration);
        assertThrows(IllegalStateException.class, generation::requirePublishing);
        assertThrows(IllegalStateException.class,
                () -> workflow(true, true, false, "42", "").requireGeneration());
        var publishing = workflow(true, false, true, " 42 ", "");
        assertEquals("42", publishing.targetUserId());
        assertDoesNotThrow(publishing::requirePublishing);
        assertThrows(IllegalStateException.class, publishing::requireGeneration);
        assertDoesNotThrow(() -> publishing.validateTarget("42"));
        assertThrows(IllegalArgumentException.class, () -> publishing.validateTarget("43"));
    }

    @Test
    void invalidTargetQuotaOrTimeoutCannotEnterTheWorkflow() {
        for (String target : new String[]{"", "0", "@username", "-42", "1".repeat(20)}) {
            assertThrows(IllegalArgumentException.class, () -> workflow(true, true, false, target, "engineering"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new XWorkflowPolicy(false, false, false, "", content(""), 0, 3,
                        Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new XWorkflowPolicy(false, false, false, "", content(""), 5, 3,
                        Duration.ZERO, Duration.ofMinutes(30), Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new XWorkflowPolicy(false, false, false, "", content(""), 5, 3,
                        Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofSeconds(59)));
    }

    private static XWorkflowPolicy workflow(boolean enabled, boolean generation, boolean publishing,
                                            String target, String direction) {
        return new XWorkflowPolicy(enabled, generation, publishing, target, content(direction), 5, 3,
                Duration.ofHours(6), Duration.ofMinutes(30), Duration.ofMinutes(5));
    }

    private static XContentPolicy content(String direction) {
        return new XContentPolicy(direction, "en", "plain", "", 1, 280);
    }
}
