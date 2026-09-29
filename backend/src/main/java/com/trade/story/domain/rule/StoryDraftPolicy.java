package com.trade.story.domain.rule;

import com.trade.story.domain.model.*;
import java.util.*;

/** Story structure, length budgeting, continuity, and fallback content rules. */
public final class StoryDraftPolicy {
    private final int sectionCount;
    private final int targetCharCount;
    public StoryDraftPolicy(int sectionCount, int targetCharCount) { this.sectionCount = sectionCount; this.targetCharCount = targetCharCount; }
    public static boolean shouldContinue(int written, int minimum, int continuation, int maximum) {
        return written < minimum && continuation < maximum;
    }
    public int continuationTargetChars(int writtenChars) {
        return clamp(targetCharCount - writtenChars, 1_800, 3_500);
    }

    public static StorySectionPlan continuationPlan(int sectionNumber, int targetChars) {
        return new StorySectionPlan()
                .setSection(sectionNumber)
                .setTitle("补充收束")
                .setSummary("在不拖沓的前提下补足关键情节、情绪回收和结局余味。")
                .setEntryState("承接前文最新状态，优先处理未回收伏笔。")
                .setKeyBeats(List.of("回收最重要的未解决矛盾", "让主角主动完成最后选择", "给核心人物关系和收益一个清楚结果"))
                .setMustPayoff("补足前文承诺过但尚未兑现的爽点和情绪回收。")
                .setExitState("主线完成，人物去向清楚，不留下影响完整性的断章。")
                .setCliffhanger("只保留轻微余味，不再开启新主线。")
                .setTargetChars(targetChars);
    }
    public StoryTopicPlan fallbackTopicPlan(
            String rawResponse,
            StoryTrendContext trendContext,
            String generationId
    ) {
        int sectionCount = Math.max(1, this.sectionCount);
        int defaultTarget = Math.max(1, targetCharCount / sectionCount);
        List<StorySectionPlan> sectionPlans = new ArrayList<>();
        for (int i = 0; i < sectionCount; i++) {
            int sectionNumber = i + 1;
            sectionPlans.add(new StorySectionPlan()
                    .setSection(sectionNumber)
                    .setTitle("Section " + sectionNumber)
                    .setSummary("Fallback story beat " + sectionNumber)
                    .setEntryState(sectionNumber == 1 ? "Fallback opening state" : "Continue from previous section")
                    .setKeyBeats(List.of("conflict", "choice", "consequence"))
                    .setMustPayoff("Advance the main conflict")
                    .setExitState(sectionNumber == sectionCount ? "Resolve the main story" : "Leave a clear next step")
                    .setCliffhanger(sectionNumber == sectionCount ? "Soft ending after resolution" : "Open next pressure point")
                    .setTargetChars(defaultTarget));
        }
        String suffix = generationId == null || generationId.length() < 8 ? "unknown" : generationId.substring(0, 8);
        String topic = firstNonBlank(firstTrendLine(trendContext), "Fallback trend topic");
        return new StoryTopicPlan()
                .setTitle("AI Story Fallback " + suffix)
                .setGenre("fallback")
                .setHotTopic(limit(topic, 120))
                .setTargetAudience("general web fiction readers")
                .setPremise("Fallback plan created because the AI topic response could not be parsed.")
                .setCorePromise("Complete a coherent story using the available trend context.")
                .setSellingPoints(List.of("clear conflict", "active protagonist", "resolved payoff"))
                .setOutline(List.of("opening conflict", "escalation", "turning point", "payoff"))
                .setSectionPlans(sectionPlans)
                .setAntiClicheRules(List.of("avoid generic AI phrasing"))
                .setStyleGuide("fast-paced, concrete, and coherent")
                .setRawResponse(rawResponse);
    }

    public StorySectionDraft fallbackSectionDraft(
            String rawResponse,
            int sectionIndex,
            StorySectionPlan sectionPlan
    ) {
        String content = firstNonBlank(
                rawResponse == null ? null : rawResponse.trim(),
                firstNonBlank(sectionPlan == null ? null : sectionPlan.getSummary(), "Fallback section content")
        );
        return new StorySectionDraft()
                .setSection(sectionIndex)
                .setSectionTitle(sectionPlan == null || !hasText(sectionPlan.getTitle())
                        ? "Section " + sectionIndex
                        : sectionPlan.getTitle())
                .setContent(content)
                .setSectionSummary(briefFromContent(content, 180))
                .setRawResponse(rawResponse);
    }

    public List<StorySectionPlan> normalizedSectionPlans(StoryTopicPlan topicPlan) {
        int sectionCount = Math.max(1, this.sectionCount);
        int defaultTarget = Math.max(1, targetCharCount / sectionCount);
        List<StorySectionPlan> normalized = new ArrayList<>();
        List<StorySectionPlan> original = topicPlan.getSectionPlans() == null
                ? List.of()
                : topicPlan.getSectionPlans();
        for (int i = 0; i < sectionCount; i++) {
            StorySectionPlan current = i < original.size() ? original.get(i) : new StorySectionPlan();
            String outlineSummary = i < topicPlan.getOutline().size() ? topicPlan.getOutline().get(i) : null;
            normalized.add(new StorySectionPlan()
                    .setSection(i + 1)
                    .setTitle(hasText(current.getTitle()) ? current.getTitle() : "第" + (i + 1) + "节")
                    .setSummary(hasText(current.getSummary()) ? current.getSummary() : outlineSummary)
                    .setEntryState(current.getEntryState())
                    .setKeyBeats(current.getKeyBeats() == null ? new ArrayList<>() : current.getKeyBeats())
                    .setMustPayoff(current.getMustPayoff())
                    .setExitState(current.getExitState())
                    .setCliffhanger(current.getCliffhanger())
                    .setTargetChars(current.getTargetChars() > 0 ? current.getTargetChars() : defaultTarget));
        }
        return normalized;
    }

    public int sectionTargetChars(int writtenChars, int remainingSections, int plannedTargetChars) {
        int remainingTarget = Math.max(1_500, targetCharCount - writtenChars);
        int balancedTarget = remainingTarget / Math.max(1, remainingSections);
        int target = plannedTargetChars > 0 ? (plannedTargetChars + balancedTarget) / 2 : balancedTarget;
        return clamp(target, 1_800, 3_500);
    }

    public static String joinContents(List<StorySectionDraft> drafts) {
        StringBuilder text = new StringBuilder();
        for (StorySectionDraft draft : drafts) {
            if (draft.getContent() != null) {
                text.append(draft.getContent()).append('\n');
            }
        }
        return text.toString();
    }

    public static String storySoFar(List<StorySectionDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (StorySectionDraft draft : drafts) {
            if (draft == null) {
                continue;
            }
            String sectionSummary = hasText(draft.getSectionSummary())
                    ? draft.getSectionSummary()
                    : briefFromContent(draft.getContent(), 180);
            if (!hasText(sectionSummary)) {
                continue;
            }
            summary.append("第").append(draft.getSection()).append("节");
            if (hasText(draft.getSectionTitle())) {
                summary.append("《").append(draft.getSectionTitle()).append("》");
            }
            summary.append("：").append(limit(sectionSummary, 240)).append('\n');
        }
        return limit(summary.toString().trim(), 2_000);
    }

    public static List<String> continuityNotes(List<StorySectionDraft> drafts) {
        Set<String> values = new LinkedHashSet<>();
        if (drafts != null) {
            for (StorySectionDraft draft : drafts) {
                addAll(values, draft == null ? null : draft.getContinuityNotes());
            }
        }
        return values.stream().limit(32).toList();
    }

    public static List<String> activeOpenLoops(List<StorySectionDraft> drafts) {
        Set<String> values = new LinkedHashSet<>();
        if (drafts != null) {
            for (StorySectionDraft draft : drafts) {
                if (draft == null) {
                    continue;
                }
                addAll(values, draft.getOpenLoops());
                for (String resolvedLoop : nullSafe(draft.getResolvedLoops())) {
                    values.remove(resolvedLoop.trim());
                }
            }
        }
        return values.stream().limit(24).toList();
    }

    public static void addAll(Set<String> target, List<String> values) {
        for (String value : nullSafe(values)) {
            if (hasText(value)) {
                target.add(value.trim());
            }
        }
    }

    public static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    public static String briefFromContent(String value, int maxChars) {
        if (!hasText(value)) {
            return "";
        }
        String text = value.replaceAll("\\s+", "");
        return limit(text, maxChars);
    }

    public static String tail(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(value.length() - maxChars);
    }

    public static int countNonWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return (int) value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .count();
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)) + "...";
    }

    public static String firstTrendLine(StoryTrendContext trendContext) {
        if (trendContext == null || !hasText(trendContext.getTrendText())) {
            return null;
        }
        for (String line : trendContext.getTrendText().split("\\R")) {
            if (hasText(line)) {
                return line.trim();
            }
        }
        return null;
    }

    public static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
