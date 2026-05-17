package com.trade.story.decision;

import com.trade.story.model.StorySectionPlan;
import com.trade.story.model.StoryTopicPlan;
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
}
