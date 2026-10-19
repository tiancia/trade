package com.trade.story.decision;

import com.trade.story.config.AiStoryProperties;
import com.trade.story.model.StorySectionDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class StoryQualityGate {
    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "命运的齿轮开始转动",
            "全场震惊",
            "空气凝固",
            "嘴角勾起一抹弧度",
            "她不知道的是",
            "从此踏上新征程"
    );
    private static final List<String> PRESSURE_MARKERS = List.of(
            "逼", "催", "欠", "骂", "羞辱", "威胁", "辞退", "报警", "封", "跪", "打", "砸", "抢", "输", "债"
    );
    private static final List<String> CHOICE_MARKERS = List.of(
            "没有", "先", "决定", "选择", "转身", "拿起", "拨通", "签", "拒绝", "盯着", "按下"
    );
    private static final List<String> COUNTER_MARKERS = List.of(
            "反手", "证据", "监控", "录音", "转给", "报警", "揭开", "识破", "反压", "还回去", "报了个数字"
    );
    private static final List<String> ACTION_MARKERS = List.of(
            "：", "\"", "“", "”", "先", "反手", "证据", "监控", "决定", "揭开", "识破", "赔偿", "转账", "报警", "后果"
    );
    private static final List<String> FINAL_NEW_HOOK_MARKERS = List.of(
            "黑色轿车", "神秘老人", "血脉组织", "真正的敌人", "才刚刚开始", "更大的阴谋", "幕后的人"
    );

    private final AiStoryProperties properties;

    public StoryQualityGate(AiStoryProperties properties) {
        this.properties = properties;
    }

    public StoryQualityReport evaluateSection(
            StorySectionDraft draft,
            int sectionIndex,
            int totalSections,
            int targetChars,
            boolean finalSection
    ) {
        List<String> issues = new ArrayList<>();
        List<String> advice = new ArrayList<>();
        String content = draft == null || draft.getContent() == null ? "" : draft.getContent();

        int forbiddenCount = countContains(content, FORBIDDEN_PHRASES);
        if (forbiddenCount > properties.getForbiddenPhraseMaxCount()) {
            issues.add("AI套话过多：" + forbiddenCount + " 处");
            advice.add("删除套话，改为具体动作、对话、证据和因果结果。");
        }
        if (sectionIndex == 1 && !openingHasRequiredShape(content)) {
            issues.add("开篇缺少明确压迫、主角选择或第一反击机会");
            advice.add("前 " + properties.getOpeningCheckChars() + " 字内补足压力事件、主动选择和可见反击机会。");
        }
        if (finalSection && containsAny(content, FINAL_NEW_HOOK_MARKERS)) {
            issues.add("最后一节引入未铺垫的新主线钩子");
            advice.add("删除新主线钩子，改为回收主线、人物关系和收益代价。");
        }
        if (draft == null || draft.getSectionSummary() == null || draft.getSectionSummary().isBlank()) {
            issues.add("缺少本节摘要");
            advice.add("补充 80 到 160 字的本节关键事实摘要。");
        }
        if (!containsAny(content, ACTION_MARKERS)) {
            issues.add("本节缺少选择、反击、揭露或局势变化");
            advice.add("加入一次由主角主动触发的选择、反击、揭露或不可逆后果。");
        }

        int score = Math.max(0, 100 - issues.size() * 18 - Math.max(0, forbiddenCount - 1) * 7);
        return new StoryQualityReport()
                .setScore(score)
                .setIssues(issues)
                .setRewriteAdvice(advice)
                .setPassed(score >= properties.getMinSectionQualityScore() && issues.isEmpty());
    }

    private boolean openingHasRequiredShape(String content) {
        String opening = limit(content, Math.max(100, properties.getOpeningCheckChars()));
        return containsAny(opening, PRESSURE_MARKERS)
                && containsAny(opening, CHOICE_MARKERS)
                && containsAny(opening, COUNTER_MARKERS);
    }

    private static int countContains(String content, List<String> markers) {
        int count = 0;
        for (String marker : markers) {
            if (content.contains(marker)) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsAny(String content, List<String> markers) {
        for (String marker : markers) {
            if (content.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
