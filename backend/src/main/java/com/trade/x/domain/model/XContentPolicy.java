package com.trade.x.domain.model;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Immutable content settings captured with each draft, independent of provider counting rules. */
public record XContentPolicy(String direction, String language, String tone, String instructions,
                             int minChars, int maxChars) {
    public XContentPolicy {
        direction = setting(direction, 1000, "direction");
        language = setting(language, 64, "language");
        tone = setting(tone, 256, "tone");
        instructions = setting(instructions, 4000, "instructions");
        if (minChars < 1 || maxChars < minChars || maxChars > 280) {
            throw new IllegalArgumentException("X content must have Unicode character bounds between 1 and 280");
        }
    }

    /** Returns the complete trimmed body; callers validate the platform limit before persisting it. */
    public String validateBody(String value) {
        if (value == null || value.isBlank() || value.length() > 20000 || hasInvalidCharacters(value)) {
            throw new IllegalArgumentException("X post body is missing or contains invalid characters");
        }
        String body = value.strip();
        int length = body.codePointCount(0, body.length());
        if (length < minChars || length > maxChars) {
            throw new IllegalArgumentException("X post body is outside its Unicode character bounds");
        }
        return body;
    }

    static boolean hasInvalidCharacters(String value) {
        return value.codePoints().anyMatch(codePoint ->
                (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t')
                        || Character.getType(codePoint) == Character.SURROGATE);
    }

    /** Exact text reuse ignoring typography; semantic originality still needs editorial review. */
    public static boolean repeatsRecentBody(String body, List<String> recentBodies) {
        String key = comparisonKey(body);
        return !key.isBlank() && recentBodies != null && recentBodies.stream()
                .anyMatch(previous -> key.equals(comparisonKey(previous)));
    }

    private static String comparisonKey(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static String setting(String value, int maxLength, String name) {
        String text = value == null ? "" : value.strip();
        if (text.length() > maxLength || hasInvalidCharacters(text)) {
            throw new IllegalArgumentException("Invalid X content " + name);
        }
        return text;
    }
}
