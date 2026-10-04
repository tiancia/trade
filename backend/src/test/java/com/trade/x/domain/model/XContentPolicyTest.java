package com.trade.x.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XContentPolicyTest {
    @Test
    void trimsSettingsAndCountsUnicodeCodePointsWithoutTruncatingTheBody() {
        var policy = new XContentPolicy(" engineering ", " en ", " plain ", " avoid hype ", 2, 3);
        assertEquals("engineering", policy.direction());
        assertEquals("en", policy.language());
        assertEquals("plain", policy.tone());
        assertEquals("avoid hype", policy.instructions());
        assertEquals("🙂中", policy.validateBody("  🙂中  "));
        assertThrows(IllegalArgumentException.class, () -> policy.validateBody("🙂"));
        assertThrows(IllegalArgumentException.class, () -> policy.validateBody("🙂中文本"));
    }

    @Test
    void rejectsImpossibleBoundsOverlongSettingsControlsAndMalformedUnicode() {
        assertThrows(IllegalArgumentException.class, () -> policy(0, 120));
        assertThrows(IllegalArgumentException.class, () -> policy(30, 20));
        assertThrows(IllegalArgumentException.class, () -> policy(1, 281));
        assertThrows(IllegalArgumentException.class,
                () -> new XContentPolicy("x".repeat(1001), "en", "plain", "", 1, 280));
        assertThrows(IllegalArgumentException.class,
                () -> new XContentPolicy("x", "en", "plain", "x".repeat(4001), 1, 280));
        var policy = policy(1, 280);
        for (String invalid : new String[]{"", "   ", "body\u0000", "\u0000body", "body\rtext", "body\uD800"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.validateBody(invalid));
        }
        assertEquals("line one\nline\ttwo", policy.validateBody("line one\nline\ttwo"));
    }

    @Test
    void permitsMissingDirectionUntilGenerationIsRequested() {
        var policy = new XContentPolicy(null, null, null, null, 1, 280);
        assertEquals("", policy.direction());
        assertEquals("", policy.instructions());
    }

    private static XContentPolicy policy(int min, int max) {
        return new XContentPolicy("engineering", "en", "plain", "", min, max);
    }
}
