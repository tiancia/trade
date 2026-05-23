package com.trade.story.decision;

import com.trade.story.model.StorySectionDraft;
import com.trade.story.model.StoryTopicPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiStoryResponseParserTest {
    private final AiStoryResponseParser parser = new AiStoryResponseParser();

    @Test
    void parsesTopicPlan() {
        StoryTopicPlan plan = parser.parseTopicPlan("""
                {
                  "title": "雨夜规则",
                  "genre": "规则怪谈",
                  "hotTopic": "城市规则怪谈与现实亲情线结合",
                  "targetAudience": "喜欢快节奏悬疑爽文的读者",
                  "premise": "林舟在雨夜规则里救回妹妹",
                  "corePromise": "主角反向利用规则，让设局者自食其果",
                  "sellingPoints": ["开局危机", "规则反转", "亲情收束"],
                  "characterBible": ["林舟：外卖员，细心但嘴硬，妹妹失踪后主动破局"],
                  "relationshipMap": ["林舟-妹妹：从误会到互相托底"],
                  "worldRules": ["雨夜八点后不能回头，否则会被规则标记"],
                  "plotThreads": ["门口纸条是谁放的"],
                  "outline": ["雨夜出事", "发现规则", "反向利用", "真相反转", "救回家人"],
                  "sectionPlan": [
                    {
                      "section": 1,
                      "title": "雨线",
                      "summary": "主角遇到第一条规则",
                      "entryState": "林舟刚下夜班",
                      "keyBeats": ["收到纸条", "试探规则", "救下邻居"],
                      "mustPayoff": "证明规则是真的",
                      "exitState": "林舟拿到第二张纸条",
                      "cliffhanger": "纸条背面有妹妹笔迹",
                      "targetChars": 2500
                    }
                  ],
                  "styleGuide": "短段落，强悬念",
                  "antiClicheRules": ["不要写命运的齿轮"]
                }
                """);

        assertEquals("雨夜规则", plan.getTitle());
        assertEquals("规则怪谈", plan.getGenre());
        assertEquals("城市规则怪谈与现实亲情线结合", plan.getHotTopic());
        assertEquals("林舟在雨夜规则里救回妹妹", plan.getPremise());
        assertEquals("主角反向利用规则，让设局者自食其果", plan.getCorePromise());
        assertEquals(3, plan.getSellingPoints().size());
        assertEquals(1, plan.getCharacterBible().size());
        assertEquals(1, plan.getWorldRules().size());
        assertEquals(1, plan.getPlotThreads().size());
        assertEquals(1, plan.getAntiClicheRules().size());
        assertEquals(1, plan.getSectionPlans().size());
        assertEquals("雨线", plan.getSectionPlans().getFirst().getTitle());
        assertEquals("林舟刚下夜班", plan.getSectionPlans().getFirst().getEntryState());
        assertEquals(3, plan.getSectionPlans().getFirst().getKeyBeats().size());
        assertEquals("证明规则是真的", plan.getSectionPlans().getFirst().getMustPayoff());
        assertEquals("纸条背面有妹妹笔迹", plan.getSectionPlans().getFirst().getCliffhanger());
        assertEquals(2500, plan.getSectionPlans().getFirst().getTargetChars());
    }

    @Test
    void parsesSectionDraftFromMarkdownFence() {
        StorySectionDraft draft = parser.parseSectionDraft("""
                ```json
                {
                  "sectionTitle": "雨线",
                  "content": "雨从晚上八点开始下。林舟发现门口多了一张纸。",
                  "endingHook": "纸上的第一条规则亮了起来。",
                  "sectionSummary": "林舟发现雨夜规则纸条，确认危险正在逼近。",
                  "continuityNotes": ["林舟住在城南老小区"],
                  "openLoops": ["纸条是谁放的"],
                  "resolvedLoops": ["林舟是否看见纸条"]
                }
                ```
                """, 1);

        assertEquals(1, draft.getSection());
        assertEquals("雨线", draft.getSectionTitle());
        assertEquals("雨从晚上八点开始下。林舟发现门口多了一张纸。", draft.getContent());
        assertEquals("林舟发现雨夜规则纸条，确认危险正在逼近。", draft.getSectionSummary());
        assertEquals("林舟住在城南老小区", draft.getContinuityNotes().getFirst());
        assertEquals("纸条是谁放的", draft.getOpenLoops().getFirst());
        assertEquals("林舟是否看见纸条", draft.getResolvedLoops().getFirst());
    }

    @Test
    void normalizesOverlongParagraphs() {
        StorySectionDraft draft = parser.parseSectionDraft("""
                {
                  "sectionTitle": "雨线",
                  "content": "正文：\\n林舟把纸条翻过来，背面只有一行歪歪扭扭的字：八点以后不要回头。他刚想笑，楼道灯忽然灭了，手机屏幕跳到八点整。门外传来妹妹的声音，可妹妹三天前已经失踪。林舟没有喊人，他先把外卖箱抵在门口，又摸出兜里的硬币，一枚一枚摆在门缝边，等那声音第二次靠近。那声音果然停在门外，指甲轻轻刮着铁皮门，像有人贴着门缝往里吸气。林舟捏住最后一枚硬币，故意让它滚出去，硬币刚越过门槛，门外的人影立刻弯下腰。"
                }
                """, 1);

        assertEquals(false, draft.getContent().startsWith("正文"));
        assertEquals(true, draft.getContent().contains("\n\n"));
    }

    @Test
    void jsonWithoutContentFails() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseSectionDraft("{\"sectionTitle\":\"空节\"}", 1)
        );

        assertEquals(true, error.getMessage().contains("missing content"));
    }
}
