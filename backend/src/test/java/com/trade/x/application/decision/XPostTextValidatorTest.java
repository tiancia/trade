package com.trade.x.application.decision;

import com.trade.x.domain.model.XContentPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XPostTextValidatorTest {
    private final XContentPolicy wide = new XContentPolicy("engineering", "en", "plain", "", 1, 280);

    @Test
    void enforcesWeightedCjkLimitInAdditionToTheConfiguredUnicodeBounds() {
        assertEquals("中".repeat(140), validate("中".repeat(140)));
        assertThrows(IllegalArgumentException.class, () -> validate("中".repeat(141)));
        assertEquals("a".repeat(280), validate("a".repeat(280)));
        assertThrows(IllegalArgumentException.class, () -> validate("a".repeat(281)));
    }

    @Test
    void longUrlsUsePlatformWeightAndComplexEmojiRemainWhole() {
        String url = "https://example.com/" + "path".repeat(12);
        String exactly280 = "中".repeat(128) + " " + url;
        assertEquals(exactly280, validate(exactly280));
        assertThrows(IllegalArgumentException.class, () -> validate("中".repeat(129) + " " + url));
        String complexEmoji = "👩🏽‍💻";
        assertEquals("中".repeat(139) + complexEmoji, validate("中".repeat(139) + complexEmoji));
        assertThrows(IllegalArgumentException.class, () -> validate("中".repeat(140) + complexEmoji));
    }

    @Test
    void normalizesNfcBeforeCountingAndPreservesTheWholeApprovedText() {
        var fourCharacters = new XContentPolicy("engineering", "fr", "plain", "", 4, 4);
        assertEquals("Café", XPostTextValidator.normalizeAndValidate("  Cafe\u0301  ", fourCharacters));
        assertThrows(IllegalArgumentException.class,
                () -> XPostTextValidator.normalizeAndValidate("abc", fourCharacters));
        assertThrows(IllegalArgumentException.class,
                () -> XPostTextValidator.normalizeAndValidate("abcde", fourCharacters));
        assertEquals("one\ntwo\tthree", validate("one\ntwo\tthree"));
    }

    @Test
    void rejectsEmptyControlsMalformedUnicodeAndPlatformForbiddenCharacters() {
        assertThrows(IllegalArgumentException.class, () -> validate(null));
        for (String invalid : new String[]{"", "   ", "a\u0000b", "a\rb", "a\uD800b", "a\uFEFFb", "a\uFFFFb"}) {
            assertThrows(IllegalArgumentException.class, () -> validate(invalid));
        }
    }

    private String validate(String body) { return XPostTextValidator.normalizeAndValidate(body, wide); }
}
