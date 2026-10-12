package com.trade.x.domain.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XContentPolicyTest {
    @Test
    void detectsTypographyOnlyRewritesButDoesNotClaimSemanticDeduplication() {
        assertTrue(XContentPolicy.repeatsRecentBody("灯还亮着——人却走远了！",
                java.util.List.of("灯还亮着，人却走远了。")));
        assertTrue(XContentPolicy.repeatsRecentBody("ＣＡＦÉ", java.util.List.of("Cafe\u0301")));
        assertFalse(XContentPolicy.repeatsRecentBody("窗前的灯替我等了一夜。",
                java.util.List.of("灯还亮着，人却走远了。")));
        assertFalse(XContentPolicy.repeatsRecentBody("新正文", java.util.List.of()));
    }

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

    @Test
    void acceptsTwoToSixQuotedDialogueLinesWithoutRewritingTheirContents() {
        var policy = dialoguePolicy();
        for (String body : new String[]{
                "\"你还没走？\"\n\"在等你。\"",
                "“一”\n“二”\n“三”\n“四”\n“五”\n“六”",
                "\"  I heard ‘stay’.  \"\n“可你说的是\"再见\"。”",
                "\"他说“别走”。\"\n“嗯。”"}) {
            assertEquals(body, policy.validateBody(body));
        }
        assertEquals("\"还在？\"\n\"嗯。\"", policy.validateBody(" \n\"还在？\"\n\"嗯。\"\n "));
    }

    @Test
    void rejectsDialogueLineCountBlankLinesUnpairedQuotesAndMultipleQuoteGroups() {
        var policy = dialoguePolicy();
        for (String invalid : new String[]{
                "\"只有一句。\"",
                "\"一句\"\n".repeat(7).strip(),
                "\"你还在？\"\n\n\"嗯。\"",
                "\"你还在？\"\n  \n\"嗯。\"",
                "你还在？\n\"嗯。\"",
                "“你还在？\"\n“嗯。”",
                "\"你还在？\"\n\"嗯。\"。",
                "\"你还在？\" \n\"嗯。\"",
                "\"你还在？\"\n \"嗯。\"",
                "\"你\t还在？\"\n\"嗯。\"",
                "\"\"\n\"嗯。\"",
                "“  ”\n“嗯。”",
                "\"一句\" \"另一句\"\n\"嗯。\"",
                "“一句”“另一句”\n“嗯。”",
                "“他说“别走”。”\n“嗯。”"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.validateBody(invalid), invalid);
        }
    }

    @Test
    void dialogueStillUsesUnicodeCodePointBoundsIncludingQuotesAndLineBreaks() {
        String body = "\"中\"\n\"🙂\"";
        var exact = new XContentPolicy("关系观察", "简体中文", "自然", "", 7, 7, XContentPolicy.Format.CHAT_DIALOGUE);
        assertEquals(body, exact.validateBody(body));
        var tooShort = new XContentPolicy("关系观察", "简体中文", "自然", "", 8, 280, XContentPolicy.Format.CHAT_DIALOGUE);
        assertThrows(IllegalArgumentException.class, () -> tooShort.validateBody(body));
        var tooLong = new XContentPolicy("关系观察", "简体中文", "自然", "", 1, 6, XContentPolicy.Format.CHAT_DIALOGUE);
        assertThrows(IllegalArgumentException.class, () -> tooLong.validateBody(body));
    }

    @Test
    void legacyConstructorsAndSavedJsonRemainFreeText() throws Exception {
        var legacy = new XContentPolicy("legacy", "English", "plain", "", 1, 280);
        assertEquals(XContentPolicy.Format.FREE_TEXT, legacy.format());
        assertEquals(legacy, new XContentPolicy("legacy", "English", "plain", "", 1, 280, null));

        String oldJson = """
                {"direction":"legacy","language":"English","tone":"plain",
                 "instructions":"","minChars":1,"maxChars":280}
                """;
        XContentPolicy restored = new ObjectMapper().readValue(oldJson, XContentPolicy.class);

        assertEquals(legacy, restored);
        assertEquals("An ordinary old post.\nNo quotes\there.",
                restored.validateBody("  An ordinary old post.\nNo quotes\there.  "));
    }

    @Test
    void dialogueJsonRoundTripRetainsItsFrozenFormatAndValidation() throws Exception {
        var json = new ObjectMapper();
        var policy = dialoguePolicy();

        XContentPolicy restored = json.readValue(json.writeValueAsString(policy), XContentPolicy.class);

        assertEquals(policy, restored);
        assertEquals("\"还在？\"\n\"嗯。\"", restored.validateBody("\"还在？\"\n\"嗯。\""));
        assertThrows(IllegalArgumentException.class, () -> restored.validateBody("An old unquoted paragraph."));
    }

    private static XContentPolicy dialoguePolicy() {
        return new XContentPolicy("关系观察", "简体中文", "自然", "", 1, 280, XContentPolicy.Format.CHAT_DIALOGUE);
    }

    private static XContentPolicy policy(int min, int max) {
        return new XContentPolicy("engineering", "en", "plain", "", min, max);
    }
}
