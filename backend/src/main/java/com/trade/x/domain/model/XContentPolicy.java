package com.trade.x.domain.model;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Immutable content settings captured with each draft, independent of provider counting rules. */
public record XContentPolicy(String direction, String language, String tone, String instructions,
                             int minChars, int maxChars, Format format) {
    public enum Format { FREE_TEXT, CHAT_DIALOGUE }

    public XContentPolicy(String direction, String language, String tone, String instructions,
                          int minChars, int maxChars) {
        this(direction, language, tone, instructions, minChars, maxChars, Format.FREE_TEXT);
    }

    public XContentPolicy {
        direction = setting(direction, 1000, "direction");
        language = setting(language, 64, "language");
        tone = setting(tone, 256, "tone");
        instructions = setting(instructions, 4000, "instructions");
        // Existing saved policy JSON has no format field and must remain free text.
        format = format == null ? Format.FREE_TEXT : format;
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
        if (format == Format.CHAT_DIALOGUE) validateDialogue(body);
        return body;
    }

    private static void validateDialogue(String body) {
        String[] lines = body.split("\\R", -1);
        if (lines.length < 2 || lines.length > 6) {
            throw new IllegalArgumentException("X dialogue must contain 2 to 6 quoted lines");
        }
        for (String line : lines) {
            if (line.length() < 3 || !line.equals(line.strip()) || line.indexOf('\t') >= 0) {
                throw new IllegalArgumentException("X dialogue lines must be nonempty with no outer whitespace or tabs");
            }
            char opening = line.charAt(0);
            char closing = line.charAt(line.length() - 1);
            if (!((opening == '"' && closing == '"') || (opening == '“' && closing == '”'))) {
                throw new IllegalArgumentException("X dialogue lines must use paired double quotes");
            }
            String text = line.substring(1, line.length() - 1);
            if (text.strip().isEmpty() || text.indexOf(opening) >= 0 || text.indexOf(closing) >= 0) {
                throw new IllegalArgumentException("X dialogue lines must contain text without nested matching quotes");
            }
        }
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
