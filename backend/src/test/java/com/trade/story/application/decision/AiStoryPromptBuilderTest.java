package com.trade.story.application.decision;

<<<<<<< HEAD:backend/src/test/java/com/trade/story/application/decision/AiStoryPromptBuilderTest.java
import com.trade.story.domain.model.StorySectionPlan;
import com.trade.story.domain.model.StoryTopicPlan;
=======
import com.trade.story.model.StorySectionPlan;
import com.trade.story.model.StorySectionDraft;
import com.trade.story.model.StoryTopicPlan;
>>>>>>> ai-story-quality-gate:backend/src/test/java/com/trade/story/decision/AiStoryPromptBuilderTest.java
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStoryPromptBuilderTest {
    private final AiStoryPromptBuilder builder = new AiStoryPromptBuilder();

    @Test
    void sectionPromptCarriesContinuityContextWithoutRawTopicResponse() {
        StoryTopicPlan plan = new StoryTopicPlan()
                .setTitle("雨夜规则")
                .setGenre("规则怪谈")
                .setHotTopic("雨夜规则")
                .setPremise("林舟在雨夜规则里救回妹妹")
                .setCorePromise("主角反向利用规则，让设局者自食其果")
                .setCharacterBible(List.of("林舟：外卖员，细心但嘴硬"))
                .setPlotThreads(List.of("纸条是谁放的"))
                .setRawResponse("RAW_TOPIC_RESPONSE_SHOULD_NOT_REPEAT");
        StorySectionPlan sectionPlan = new StorySectionPlan()
                .setSection(2)
                .setTitle("反向试探")
                .setSummary("林舟利用硬币确认门外不是妹妹")
                .setKeyBeats(List.of("假声音逼近", "林舟试探", "规则反噬"))
                .setMustPayoff("确认纸条规则可靠")
                .setExitState("林舟拿到第二张纸条");

        String prompt = builder.buildSectionPrompt(
                plan,
                sectionPlan,
                2,
                6,
                2500,
                15000,
                2600,
                "第1节《雨线》：林舟发现第一张纸条，妹妹的声音在门外响起。",
                List.of("林舟住在城南老小区", "妹妹已经失踪三天"),
                List.of("纸条是谁放的"),
                "门外那声音轻轻喊：哥，开门。",
                false
        );

        assertTrue(prompt.contains("已写剧情摘要"));
        assertTrue(prompt.contains("林舟住在城南老小区"));
        assertTrue(prompt.contains("纸条是谁放的"));
        assertTrue(prompt.contains("门外那声音轻轻喊"));
        assertTrue(prompt.contains("sectionSummary"));
        assertFalse(prompt.contains("RAW_TOPIC_RESPONSE_SHOULD_NOT_REPEAT"));
    }

    @Test
    void rewritePromptIncludesQualityIssuesAndOriginalSection() {
        StoryTopicPlan plan = new StoryTopicPlan()
                .setTitle("雨夜规则")
                .setGenre("规则怪谈")
                .setHotTopic("城市规则怪谈")
                .setPremise("林舟在雨夜规则里救回妹妹")
                .setCorePromise("主角反向利用规则破局");
        StorySectionPlan sectionPlan = new StorySectionPlan()
                .setSection(1)
                .setTitle("雨线")
                .setSummary("林舟发现第一张规则纸条")
                .setKeyBeats(List.of("收到纸条", "主动试探", "确认危险"));
        StorySectionDraft draft = new StorySectionDraft()
                .setSection(1)
                .setSectionTitle("雨线")
                .setContent("命运的齿轮开始转动，全场震惊。")
                .setSectionSummary("开篇只有套话。");
        StoryQualityReport report = new StoryQualityReport()
                .setScore(40)
                .setIssues(List.of("AI套话过多", "开篇缺少明确压迫"))
                .setRewriteAdvice(List.of("删除套话", "补足压迫、选择和反击"));

        String prompt = builder.buildSectionRewritePrompt(
                plan,
                sectionPlan,
                draft,
                report,
                1,
                6,
                2400,
                "无",
                List.of(),
                List.of(),
                "无",
                false
        );

        assertTrue(prompt.contains("重写第 1/6 节"));
        assertTrue(prompt.contains("AI套话过多"));
        assertTrue(prompt.contains("补足压迫、选择和反击"));
        assertTrue(prompt.contains("命运的齿轮开始转动"));
        assertTrue(prompt.contains("只返回一个 JSON object"));
    }

    @Test
    void finalEditPromptRequiresSameCoreEventsAndEditedSectionsJson() {
        StoryTopicPlan plan = new StoryTopicPlan()
                .setTitle("雨夜规则")
                .setGenre("规则怪谈")
                .setHotTopic("城市规则怪谈")
                .setPremise("林舟在雨夜规则里救回妹妹");
        StorySectionDraft draft = new StorySectionDraft()
                .setSection(1)
                .setSectionTitle("雨线")
                .setContent("房东把欠条拍在桌上。林舟反手拨通电话。")
                .setSectionSummary("林舟被催债后开始反击。");

        String prompt = builder.buildFinalEditPrompt(plan, List.of(draft), 1200);

        assertTrue(prompt.contains("最终编辑润色"));
        assertTrue(prompt.contains("不能改变核心事件"));
        assertTrue(prompt.contains("editedSections"));
        assertTrue(prompt.contains("房东把欠条拍在桌上"));
    }
}
