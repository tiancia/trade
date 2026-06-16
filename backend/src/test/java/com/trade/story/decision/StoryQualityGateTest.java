package com.trade.story.decision;

import com.trade.story.config.AiStoryProperties;
import com.trade.story.model.StorySectionDraft;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryQualityGateTest {
    private final AiStoryProperties properties = new AiStoryProperties();
    private final StoryQualityGate gate = new StoryQualityGate(properties);

    @Test
    void rejectsStockAiPhrases() {
        StorySectionDraft draft = draft("""
                命运的齿轮开始转动，全场震惊。
                林舟嘴角勾起一抹弧度，空气凝固了。
                """);

        StoryQualityReport report = gate.evaluateSection(draft, 1, 6, 2400, false);

        assertFalse(report.isPassed());
        assertTrue(report.getIssues().stream().anyMatch(issue -> issue.contains("AI套话")));
        assertTrue(report.getScore() < properties.getMinSectionQualityScore());
    }

    @Test
    void rejectsWeakOpeningWithoutPressureChoiceAndCounterattack() {
        StorySectionDraft draft = draft("""
                林舟醒来时阳光很好。他想起很多往事，觉得人生应该重新开始。
                他走在路上，看见城市慢慢苏醒，心里生出一种难以言说的期待。
                """);

        StoryQualityReport report = gate.evaluateSection(draft, 1, 6, 2400, false);

        assertFalse(report.isPassed());
        assertTrue(report.getIssues().stream().anyMatch(issue -> issue.contains("开篇")));
    }

    @Test
    void rejectsFinalSectionThatOpensUnseededMainHook() {
        StorySectionDraft draft = draft("""
                林舟把欠款还清，旧案也终于结了。
                深夜，一辆黑色轿车停在街角。神秘老人走下车，说真正的血脉组织刚刚找到他。
                """).setSectionSummary("主线收束后突然出现新的神秘组织。");

        StoryQualityReport report = gate.evaluateSection(draft, 6, 6, 2400, true);

        assertFalse(report.isPassed());
        assertTrue(report.getIssues().stream().anyMatch(issue -> issue.contains("最后一节")));
    }

    @Test
    void acceptsConcreteActionDrivenSection() {
        StorySectionDraft draft = draft("""
                房东把欠条拍在桌上：“今晚十二点前，三千二，一分不能少。”
                林舟看了一眼手机余额，只有四百六。他没有解释，先把外卖箱压住门缝，反手拨通旧货市场老板的电话。
                “那只青花碗我不退。”他说，“但我要你现在把摊位监控发给我。”
                老板骂了两句，还是发来视频。画面里，王经理的司机把纸箱放到摊边，转身就走。
                林舟把视频转给苏晴，又把欠条折进钱包。半小时后，王经理主动打电话，声音发虚：“小陆，赔偿金我重新算。”
                林舟报了个数字：“三万二，今晚十二点前，一分不能少。”
                """).setSectionSummary("林舟被房东催债后主动追查青花碗来源，用监控证据反压王经理。");

        StoryQualityReport report = gate.evaluateSection(draft, 1, 6, 2400, false);

        assertTrue(report.isPassed(), () -> String.join("; ", report.getIssues()));
        assertTrue(report.getScore() >= properties.getMinSectionQualityScore());
    }

    private static StorySectionDraft draft(String content) {
        return new StorySectionDraft()
                .setSection(1)
                .setSectionTitle("测试")
                .setContent(content)
                .setSectionSummary("测试摘要");
    }
}
