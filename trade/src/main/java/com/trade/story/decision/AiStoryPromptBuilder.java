package com.trade.story.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.story.config.AiStoryProperties;
import com.trade.story.model.StorySectionPlan;
import com.trade.story.model.StoryTopicPlan;
import com.trade.story.model.StoryTrendContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiStoryPromptBuilder {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String buildTopicPrompt(
            StoryTrendContext trendContext,
            List<String> recentStoryNames,
            AiStoryProperties properties
    ) {
        return """
                你是中文网络小说平台的原创短篇策划编辑和作者，目标平台是%s。
                你需要先选择一个更容易吸引中文网文读者的热门题材，再规划一篇完整原创短篇爽文小说。
                只返回一个 JSON object，不要输出 markdown、注释或额外解释。

                必须返回的 JSON schema：
                {
                  "title": "原创中文书名",
                  "genre": "题材类型",
                  "hotTopic": "你选择的热门题材，一句话说明",
                  "targetAudience": "目标读者",
                  "premise": "一句话故事前提，包含主角、处境、核心矛盾",
                  "corePromise": "读者追读承诺：主角最终会怎样爽、怎样赢",
                  "sellingPoints": ["爽点或期待感1", "爽点或期待感2", "爽点或期待感3"],
                  "characterBible": ["角色名：身份、目标、弱点、说话习惯、与主角关系"],
                  "relationshipMap": ["角色A-角色B：关系变化和冲突焦点"],
                  "worldRules": ["世界/金手指/职业/时代规则，后文必须遵守"],
                  "plotThreads": ["需要铺垫并回收的伏笔、矛盾或承诺"],
                  "outline": ["开端", "发展", "反转", "高潮", "结局"],
                  "sectionPlan": [
                    {
                      "section": 1,
                      "title": "小节标题",
                      "summary": "本节剧情摘要",
                      "entryState": "本节开场时主角处境",
                      "keyBeats": ["压迫/诱因", "主角主动选择", "反击或破局", "代价或新问题"],
                      "mustPayoff": "本节必须兑现的爽点或伏笔",
                      "exitState": "本节结束时人物关系、资源、风险的变化",
                      "cliffhanger": "自然钩子；最后一节写收束余味",
                      "targetChars": 2500
                    }
                  ],
                  "styleGuide": "行文风格要求",
                  "antiClicheRules": ["要避免的AI味套话或空泛写法"]
                }

                规则：
                - 小说必须是中文原创内容，不能续写、仿写或借用现有小说、影视、动漫、游戏的书名、角色、世界观、具体桥段或作者风格。
                - 可以参考榜单和题材文本中的“题材趋势”，但不要复制榜单作品名称、人物名、设定名和宣传语。
                - 面向免费阅读平台读者，开篇 800 字内必须出现明确压迫、主角选择和第一个反击机会。
                - 爽文重点是“压迫足、反击狠、收益明、后患清楚”，不要只用旁白说主角很强。
                - 主角必须主动做选择，不能靠巧合、路人解释、外挂自动解决核心冲突。
                - 每个重要设定、道具、能力、人物关系都要在 sectionPlan 中安排铺垫和回收，避免后文突然出现。
                - 降低 AI 味：少用“命运的齿轮开始转动、全场震惊、嘴角勾起一抹弧度、空气凝固、她不知道的是”等空泛套话；多用具体动作、对话、场景细节和因果结果。
                - 人物说话要有身份差异，反派不能只会喊叫，配角不能只负责震惊。
                - 内容适合大众平台发布，避免露骨色情、极端血腥、仇恨、违法教程、未成年人不当内容。
                - 按 targetCharCount=%d 和 sectionCount=%d 规划，sectionPlan 数量必须等于 sectionCount。
                - 每个 sectionPlan.targetChars 尽量均分总字数，整篇最终约 1.5 万中文字符。

                热门题材/榜单上下文采集时间：%s
                热门题材/榜单来源：%s

                热门题材/榜单文本：
                %s

                最近已生成的本地小说文件名，避免重复选题：
                %s
                """.formatted(
                properties.getTargetPlatform(),
                properties.getTargetCharCount(),
                properties.getSectionCount(),
                trendContext.getCollectedAt(),
                trendContext.getSources(),
                trendContext.getTrendText(),
                recentStoryNames
        );
    }

    public String buildSectionPrompt(
            StoryTopicPlan plan,
            StorySectionPlan sectionPlan,
            int sectionIndex,
            int totalSections,
            int targetChars,
            int targetStoryChars,
            int writtenChars,
            String previousEnding,
            boolean finalSection
    ) {
        return buildSectionPrompt(
                plan,
                sectionPlan,
                sectionIndex,
                totalSections,
                targetChars,
                targetStoryChars,
                writtenChars,
                "",
                List.of(),
                List.of(),
                previousEnding,
                finalSection
        );
    }

    public String buildSectionPrompt(
            StoryTopicPlan plan,
            StorySectionPlan sectionPlan,
            int sectionIndex,
            int totalSections,
            int targetChars,
            int targetStoryChars,
            int writtenChars,
            String storySoFar,
            List<String> continuityNotes,
            List<String> openLoops,
            String previousEnding,
            boolean finalSection
    ) {
        return """
                你是中文网络小说作者。请根据整体规划和已写剧情，续写第 %d/%d 节正文。
                只返回一个 JSON object，不要输出 markdown、注释或额外解释。

                必须返回的 JSON schema：
                {
                  "sectionTitle": "本节标题",
                  "content": "本节中文正文，不要包含 JSON 之外的说明",
                  "endingHook": "本节结尾钩子或收束说明",
                  "sectionSummary": "80到160字概括本节已经发生的关键事实，供下一节续写",
                  "continuityNotes": ["本节新增或确认的连续性事实：人物状态、资源、伤病、地点、关系、时间"],
                  "openLoops": ["本节结尾仍未解决的伏笔、危险、承诺"],
                  "resolvedLoops": ["本节已经回收或解决的伏笔"]
                }

                写作规则：
                - 正文必须是中文原创小说，不要套用现有 IP、作品名、角色名、名场面或作者风格。
                - 本节目标长度约 %d 个中文字符，允许上下浮动，但不要明显短篇化。
                - 当前已经写出约 %d 个非空白字符，整篇目标约 %d 个非空白字符。
                - 必须接续“已写剧情摘要、连续性台账、上一节结尾片段”，不要重启故事、不要改名、不要改设定、不要跳过已承诺的冲突。
                - 本节必须完成“当前小节规划 JSON”里的 keyBeats、mustPayoff 和 exitState；若和已写事实冲突，以已写事实为准，改成自然推进。
                - 保持番茄等免费网文平台节奏：开门见冲突，段落短，信息清楚，情绪推进快，每 300 到 500 字至少有一次选择、反击、揭露或局势变化。
                - 爽点要写出过程：先给压力和羞辱，再让主角凭已有能力或信息反击，最后给明确收益、代价或新麻烦。
                - 降低 AI 味：少用抽象总结和万能句式，不写“命运的齿轮开始转动、全场震惊、空气凝固、嘴角勾起、她不知道的是、从此踏上新征程”等套话。
                - 多写可感知细节：手上动作、账目数字、具体物件、对话里的试探和威胁；少写空泛形容词。
                - 对话要推动剧情，人物语气要符合身份；反派要有利益动机，不能只负责衬托主角。
                - content 内段落要短，单段尽量不超过 120 个中文字符；不要把整节写成一整段。
                - 不要在 content 里写“小节标题”“字数统计”“下面开始”“本章”等元说明。
                - %s

                整体规划 JSON：
                %s

                当前小节规划 JSON：
                %s

                已写剧情摘要：
                %s

                连续性台账 JSON：
                %s

                未回收伏笔 JSON：
                %s

                上一节结尾片段：
                %s
                """.formatted(
                sectionIndex,
                totalSections,
                targetChars,
                writtenChars,
                targetStoryChars,
                finalSection ? "这是最后一节，需要完成主线高潮和结局，结尾可以保留轻微余味但不能断章。" : "本节结尾要留下自然钩子，方便下一节继续推进。",
                toJson(promptPlanView(plan)),
                toJson(sectionPlan),
                storySoFar == null || storySoFar.isBlank() ? "无" : storySoFar,
                toJson(continuityNotes == null ? List.of() : continuityNotes),
                toJson(openLoops == null ? List.of() : openLoops),
                previousEnding == null || previousEnding.isBlank() ? "无" : previousEnding
        );
    }

    private static Map<String, Object> promptPlanView(StoryTopicPlan plan) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("title", plan.getTitle());
        view.put("genre", plan.getGenre());
        view.put("hotTopic", plan.getHotTopic());
        view.put("targetAudience", plan.getTargetAudience());
        view.put("premise", plan.getPremise());
        view.put("corePromise", plan.getCorePromise());
        view.put("sellingPoints", plan.getSellingPoints());
        view.put("characterBible", plan.getCharacterBible());
        view.put("relationshipMap", plan.getRelationshipMap());
        view.put("worldRules", plan.getWorldRules());
        view.put("plotThreads", plan.getPlotThreads());
        view.put("outline", plan.getOutline());
        view.put("sectionPlans", plan.getSectionPlans());
        view.put("styleGuide", plan.getStyleGuide());
        view.put("antiClicheRules", plan.getAntiClicheRules());
        return view;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Serialize story prompt context error", e);
        }
    }
}
