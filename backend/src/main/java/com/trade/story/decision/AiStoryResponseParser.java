package com.trade.story.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.story.model.StorySectionDraft;
import com.trade.story.model.StorySectionPlan;
import com.trade.story.model.StoryTopicPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses AI story planning/drafting responses while accepting common Chinese
 * and English field aliases from different model outputs.
 */
@Component
public class AiStoryResponseParser {
    private static final int MAX_PARAGRAPH_CHARS = 180;
    private static final int MIN_SPLIT_PARAGRAPH_CHARS = 70;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public StoryTopicPlan parseTopicPlan(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalArgumentException("AI story topic response is empty");
        }

        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(rawResponse));
            StoryTopicPlan plan = new StoryTopicPlan()
                    .setTitle(firstText(root, "title", "bookTitle", "name"))
                    .setGenre(firstText(root, "genre", "category"))
                    .setHotTopic(firstText(root, "hotTopic", "topic", "theme"))
                    .setTargetAudience(firstText(root, "targetAudience", "audience"))
                    .setPremise(firstText(root, "premise", "logline", "corePremise", "故事前提"))
                    .setCorePromise(firstText(root, "corePromise", "promise", "payoffPromise", "读者承诺"))
                    .setSellingPoints(readStringArray(firstExisting(root, "sellingPoints", "hooks", "卖点")))
                    .setCharacterBible(readStringArray(firstExisting(root, "characterBible", "characters", "人物设定")))
                    .setRelationshipMap(readStringArray(firstExisting(root, "relationshipMap", "relationships", "关系图")))
                    .setWorldRules(readStringArray(firstExisting(root, "worldRules", "rules", "settings", "设定规则")))
                    .setPlotThreads(readStringArray(firstExisting(root, "plotThreads", "foreshadowing", "openLoops", "伏笔")))
                    .setOutline(readStringArray(firstExisting(root, "outline", "plotOutline", "大纲")))
                    .setSectionPlans(readSectionPlans(firstExisting(root, "sectionPlan", "sectionPlans", "chapterPlan", "chapters")))
                    .setAntiClicheRules(readStringArray(firstExisting(root, "antiClicheRules", "forbiddenPatterns", "avoid", "避雷")))
                    .setStyleGuide(firstText(root, "styleGuide", "style"))
                    .setRawResponse(rawResponse);

            if (!hasText(plan.getTitle())) {
                throw new IllegalArgumentException("AI story topic response missing title");
            }
            if (!hasText(plan.getHotTopic())) {
                throw new IllegalArgumentException("AI story topic response missing hotTopic");
            }
            return plan;
        } catch (Exception e) {
            throw new IllegalArgumentException("Parse AI story topic response failed: " + e.getMessage(), e);
        }
    }

    public StorySectionDraft parseSectionDraft(String rawResponse, int sectionNumber) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalArgumentException("AI story section response is empty");
        }

        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(rawResponse));
            String content = firstText(root, "content", "正文", "story", "text");
            if (!hasText(content)) {
                throw new IllegalArgumentException("AI story section response missing content");
            }
            return new StorySectionDraft()
                    .setSection(sectionNumber)
                    .setSectionTitle(firstText(root, "sectionTitle", "chapterTitle", "title"))
                    .setContent(normalizeContent(content))
                    .setEndingHook(firstText(root, "endingHook", "hook"))
                    .setSectionSummary(firstText(root, "sectionSummary", "summary", "本节摘要"))
                    .setContinuityNotes(readStringArray(firstExisting(root, "continuityNotes", "stateUpdates", "facts", "连续性台账")))
                    .setOpenLoops(readStringArray(firstExisting(root, "openLoops", "unresolvedHooks", "unresolvedThreads", "未回收伏笔")))
                    .setResolvedLoops(readStringArray(firstExisting(root, "resolvedLoops", "payoffs", "resolvedThreads", "已回收伏笔")))
                    .setRawResponse(rawResponse);
        } catch (Exception jsonError) {
            if (looksLikeJson(rawResponse)) {
                throw new IllegalArgumentException(
                        "Parse AI story section response failed: " + jsonError.getMessage(),
                        jsonError
                );
            }
            // Some models return plain prose for section bodies. Treat non-JSON
            // prose as usable content instead of losing the whole generation.
            String text = stripMarkdownFence(rawResponse).trim();
            if (!hasText(text)) {
                throw new IllegalArgumentException(
                        "Parse AI story section response failed: " + jsonError.getMessage(),
                        jsonError
                );
            }
            return new StorySectionDraft()
                    .setSection(sectionNumber)
                    .setSectionTitle("第" + sectionNumber + "节")
                    .setContent(normalizeContent(text))
                    .setRawResponse(rawResponse);
        }
    }

    private static List<StorySectionPlan> readSectionPlans(JsonNode node) {
        List<StorySectionPlan> plans = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return plans;
        }
        int index = 1;
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                plans.add(new StorySectionPlan()
                        .setSection(index)
                        .setTitle("第" + index + "节")
                        .setSummary(item.asText()));
            } else {
                plans.add(new StorySectionPlan()
                        .setSection(intOrDefault(item.path("section"), index))
                        .setTitle(firstText(item, "title", "sectionTitle", "chapterTitle"))
                        .setSummary(firstText(item, "summary", "plot", "description"))
                        .setEntryState(firstText(item, "entryState", "startingState", "startState"))
                        .setKeyBeats(readStringArray(firstExisting(item, "keyBeats", "beats", "plotBeats")))
                        .setMustPayoff(firstText(item, "mustPayoff", "payoff", "requiredPayoff"))
                        .setExitState(firstText(item, "exitState", "endingState", "endState"))
                        .setCliffhanger(firstText(item, "cliffhanger", "hook", "endingHook"))
                        .setTargetChars(intOrDefault(item.path("targetChars"), 0)));
            }
            index++;
        }
        return plans;
    }

    private static List<String> readStringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (hasText(value)) {
                values.add(value.trim());
            }
            return values;
        }
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && hasText(item.asText(null))) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private static JsonNode firstExisting(JsonNode root, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = root.get(fieldName);
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static String firstText(JsonNode root, String... fieldNames) {
        JsonNode node = firstExisting(root, fieldNames);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private static int intOrDefault(JsonNode node, int fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt(fallback);
        }
        try {
            return Integer.parseInt(node.asText().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String extractJsonObject(String rawResponse) {
        String text = stripMarkdownFence(rawResponse);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String stripMarkdownFence(String rawResponse) {
        String text = rawResponse == null ? "" : rawResponse.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd >= 0) {
                text = text.substring(firstLineEnd + 1).trim();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        return text;
    }

    private static boolean looksLikeJson(String rawResponse) {
        String text = stripMarkdownFence(rawResponse);
        return text.startsWith("{") || text.startsWith("[");
    }

    private static String normalizeContent(String content) {
        // Normalize for text-file readability: remove header noise, standardize
        // newlines, and split overly long paragraphs at sentence boundaries.
        String text = stripLeadingMetaLines(content)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (!hasText(text)) {
            return text;
        }

        List<String> paragraphs = new ArrayList<>();
        for (String block : text.split("\\n\\s*\\n")) {
            for (String line : block.split("\\n")) {
                String paragraph = line.trim();
                if (!hasText(paragraph)) {
                    continue;
                }
                paragraphs.addAll(splitLongParagraph(paragraph));
            }
        }
        return String.join("\n\n", paragraphs).trim();
    }

    private static String stripLeadingMetaLines(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\\n");
        int start = 0;
        while (start < lines.length && start < 3) {
            String line = lines[start].trim();
            if (line.matches("^(正文|本节正文|小说正文)[:：]?$")
                    || line.matches("^第[0-9一二三四五六七八九十百]+[章节].*$")) {
                start++;
                continue;
            }
            break;
        }
        if (start == 0) {
            return text;
        }
        StringBuilder stripped = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (!stripped.isEmpty()) {
                stripped.append('\n');
            }
            stripped.append(lines[i]);
        }
        return stripped.toString();
    }

    private static List<String> splitLongParagraph(String paragraph) {
        if (paragraph.length() <= MAX_PARAGRAPH_CHARS) {
            return List.of(paragraph);
        }

        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < paragraph.length(); i++) {
            char ch = paragraph.charAt(i);
            current.append(ch);
            if ((current.length() >= MIN_SPLIT_PARAGRAPH_CHARS && isSentenceBreak(ch))
                    || current.length() >= MAX_PARAGRAPH_CHARS + MIN_SPLIT_PARAGRAPH_CHARS) {
                addParagraph(values, current);
            }
        }
        addParagraph(values, current);
        return values;
    }

    private static void addParagraph(List<String> values, StringBuilder current) {
        String value = current.toString().trim();
        if (hasText(value)) {
            values.add(value);
        }
        current.setLength(0);
    }

    private static boolean isSentenceBreak(char ch) {
        return ch == '。'
                || ch == '！'
                || ch == '？'
                || ch == '；'
                || ch == '!'
                || ch == '?'
                || ch == ';'
                || ch == '”'
                || ch == '’';
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
