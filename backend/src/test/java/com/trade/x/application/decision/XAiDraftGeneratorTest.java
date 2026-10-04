package com.trade.x.application.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.client.ai.AiTextClient;
import com.trade.x.domain.model.XContentPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class XAiDraftGeneratorTest {
    private final XContentPolicy content = new XContentPolicy("软件工程实践", "简体中文", "自然克制",
            "不写未经核实的新闻；只写可验证的方法", 4, 120);

    @Test
    void promptContainsConfiguredIntentBoundsAndFactConstraintsAndOutputIsNormalized() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AiTextClient ai = value -> {
            prompt.set(value);
            return "{\"body\":\"Cafe\\u0301\",\"reviewNote\":\"  一般观点，无实时来源  \"}";
        };
        var generator = new XAiDraftGenerator(ai, new ObjectMapper());

        var draft = generator.generate(content);

        assertEquals("Café", draft.body());
        assertEquals("一般观点，无实时来源", draft.reviewNote());
        assertTrue(prompt.get().contains(content.direction()));
        assertTrue(prompt.get().contains(content.language()));
        assertTrue(prompt.get().contains(content.tone()));
        assertTrue(prompt.get().contains(content.instructions()));
        assertTrue(prompt.get().contains("4–120"));
        assertTrue(prompt.get().contains("280 加权字符"));
        assertTrue(prompt.get().contains("不得臆造实时事件、数字事实"));
        assertTrue(prompt.get().contains("不得截断正文"));
    }

    @Test
    void malformedMissingOverlongAndPlatformInvalidResponsesNeverBecomeDrafts() {
        String[] invalid = {
                null, "", "not JSON", "{}", "{\"body\":1,\"reviewNote\":\"note\"}",
                "{\"body\":\"valid body\",\"reviewNote\":null}",
                "{\"body\":\"abc\",\"reviewNote\":\"note\"}",
                "{\"body\":\"valid body\",\"reviewNote\":\" \"}",
                "{\"body\":\"valid body\",\"reviewNote\":\"note\"} {}",
                "x".repeat(40001)
        };
        for (String response : invalid) {
            var generator = new XAiDraftGenerator(prompt -> response);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> generator.generate(content));
            assertEquals("X AI draft generation failed", failure.getMessage());
            assertNull(failure.getCause());
        }
        var broad = new XContentPolicy("engineering", "en", "plain", "", 1, 280);
        var cjkOverLimit = new XAiDraftGenerator(prompt -> "{\"body\":\"" + "中".repeat(141) + "\",\"reviewNote\":\"note\"}");
        assertThrows(IllegalStateException.class, () -> cjkOverLimit.generate(broad));
        var noteOverLimit = new XAiDraftGenerator(prompt -> "{\"body\":\"valid body\",\"reviewNote\":\"" + "n".repeat(2001) + "\"}");
        assertThrows(IllegalStateException.class, () -> noteOverLimit.generate(content));
    }

    @Test
    void providerErrorsDoNotExposePayloadsOrCredentialsAndMissingDirectionMakesNoRequest() {
        var generator = new XAiDraftGenerator(prompt -> { throw new IllegalStateException("secret-token-provider-payload"); });
        var failure = assertThrows(IllegalStateException.class, () -> generator.generate(content));
        assertFalse(failure.toString().contains("secret-token"));
        assertNull(failure.getCause());
        AtomicReference<String> prompt = new AtomicReference<>();
        var missingDirection = new XAiDraftGenerator(value -> { prompt.set(value); return "{}"; });
        assertThrows(IllegalArgumentException.class,
                () -> missingDirection.generate(new XContentPolicy("", "en", "plain", "", 1, 280)));
        assertNull(prompt.get());
    }
}
