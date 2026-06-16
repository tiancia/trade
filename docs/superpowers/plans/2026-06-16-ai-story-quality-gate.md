# AI Story Quality Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add bounded quality checks, section rewrite, and final editor pass to the backend AI story generator so generated short novels read less like generic AI output.

**Architecture:** Keep the existing story pipeline and add focused collaborators around it. `StoryQualityGate` scores generated sections locally, `StoryQualityReport` carries rewrite advice, `AiStoryPromptBuilder` gains rewrite/editor prompts, `AiStoryResponseParser` parses final edited sections, and `AiStoryService` orchestrates retry-limited rewrite plus final editing.

**Tech Stack:** Java 21, Spring Boot, JUnit 6, Maven, existing `AiTextClient` JSON generation abstraction.

---

## File Structure

- Create `backend/src/main/java/com/trade/story/decision/StoryQualityReport.java`: immutable-ish report model for quality gate results.
- Create `backend/src/main/java/com/trade/story/decision/StoryQualityGate.java`: deterministic checks for forbidden phrases, opening shape, final-section hooks, section summaries, and basic action density.
- Modify `backend/src/main/java/com/trade/story/config/AiStoryProperties.java`: add quality-gate configuration knobs with safe defaults.
- Modify `backend/src/main/java/com/trade/story/decision/AiStoryPromptBuilder.java`: add rewrite and final editor prompts; strengthen topic prompt with candidate scoring language.
- Modify `backend/src/main/java/com/trade/story/decision/AiStoryResponseParser.java`: parse structured final editor response into section drafts.
- Modify `backend/src/main/java/com/trade/story/application/AiStoryService.java`: inject `StoryQualityGate`, evaluate sections, rewrite failed sections once by default, run final editor pass, and keep existing fallback behavior.
- Create `backend/src/test/java/com/trade/story/decision/StoryQualityGateTest.java`: unit tests for local quality rules.
- Modify `backend/src/test/java/com/trade/story/decision/AiStoryPromptBuilderTest.java`: test rewrite/editor prompt context and constraints.
- Modify `backend/src/test/java/com/trade/story/decision/AiStoryResponseParserTest.java`: test final editor parsing.
- Modify or create `backend/src/test/java/com/trade/story/application/AiStoryServiceTest.java`: fake AI client tests for rewrite and final editor orchestration.

---

### Task 1: Story Quality Gate Model And Rules

**Files:**
- Create: `backend/src/main/java/com/trade/story/decision/StoryQualityReport.java`
- Create: `backend/src/main/java/com/trade/story/decision/StoryQualityGate.java`
- Test: `backend/src/test/java/com/trade/story/decision/StoryQualityGateTest.java`

- [ ] **Step 1: Write failing quality gate tests**

Create `StoryQualityGateTest` with these tests:

```java
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
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
cd backend
.\mvnw -Dtest=StoryQualityGateTest test
```

Expected: compilation failure because `StoryQualityGate` and `StoryQualityReport` do not exist.

- [ ] **Step 3: Implement minimal quality report**

Create `StoryQualityReport`:

```java
package com.trade.story.decision;

import java.util.ArrayList;
import java.util.List;

public class StoryQualityReport {
    private boolean passed;
    private int score;
    private List<String> issues = new ArrayList<>();
    private List<String> rewriteAdvice = new ArrayList<>();

    public boolean isPassed() {
        return passed;
    }

    public StoryQualityReport setPassed(boolean passed) {
        this.passed = passed;
        return this;
    }

    public int getScore() {
        return score;
    }

    public StoryQualityReport setScore(int score) {
        this.score = score;
        return this;
    }

    public List<String> getIssues() {
        return issues;
    }

    public StoryQualityReport setIssues(List<String> issues) {
        this.issues = issues == null ? new ArrayList<>() : new ArrayList<>(issues);
        return this;
    }

    public List<String> getRewriteAdvice() {
        return rewriteAdvice;
    }

    public StoryQualityReport setRewriteAdvice(List<String> rewriteAdvice) {
        this.rewriteAdvice = rewriteAdvice == null ? new ArrayList<>() : new ArrayList<>(rewriteAdvice);
        return this;
    }
}
```

- [ ] **Step 4: Add configuration fields**

In `AiStoryProperties`, add:

```java
private boolean qualityGateEnabled = true;
private int maxSectionRewriteAttempts = 1;
private boolean finalEditorPassEnabled = true;
private int minSectionQualityScore = 70;
private int forbiddenPhraseMaxCount = 1;
private int openingCheckChars = 800;
```

- [ ] **Step 5: Implement minimal quality gate**

Create `StoryQualityGate`:

```java
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
```

- [ ] **Step 6: Run tests and verify GREEN**

Run:

```powershell
cd backend
.\mvnw -Dtest=StoryQualityGateTest test
```

Expected: all `StoryQualityGateTest` tests pass.

- [ ] **Step 7: Commit Task 1**

```powershell
git add backend/src/main/java/com/trade/story/config/AiStoryProperties.java backend/src/main/java/com/trade/story/decision/StoryQualityGate.java backend/src/main/java/com/trade/story/decision/StoryQualityReport.java backend/src/test/java/com/trade/story/decision/StoryQualityGateTest.java
git commit -m "feat: add story quality gate"
```

---

### Task 2: Rewrite And Final Editor Prompts

**Files:**
- Modify: `backend/src/main/java/com/trade/story/decision/AiStoryPromptBuilder.java`
- Test: `backend/src/test/java/com/trade/story/decision/AiStoryPromptBuilderTest.java`

- [ ] **Step 1: Write failing prompt builder tests**

Add tests to `AiStoryPromptBuilderTest`:

```java
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
```

- [ ] **Step 2: Run prompt tests and verify RED**

Run:

```powershell
cd backend
.\mvnw -Dtest=AiStoryPromptBuilderTest test
```

Expected: compilation failure because `buildSectionRewritePrompt` and `buildFinalEditPrompt` do not exist.

- [ ] **Step 3: Implement prompt methods**

Add methods to `AiStoryPromptBuilder`:

```java
public String buildSectionRewritePrompt(
        StoryTopicPlan plan,
        StorySectionPlan sectionPlan,
        StorySectionDraft originalDraft,
        StoryQualityReport qualityReport,
        int sectionIndex,
        int totalSections,
        int targetChars,
        String storySoFar,
        List<String> continuityNotes,
        List<String> openLoops,
        String previousEnding,
        boolean finalSection
) {
    return """
            你是中文短篇网文编辑。请根据质检问题重写第 %d/%d 节。
            只返回一个 JSON object，不要输出 markdown、注释或额外解释。

            必须返回的 JSON schema：
            {
              "sectionTitle": "本节标题",
              "content": "重写后的本节中文正文",
              "endingHook": "本节结尾钩子或收束说明",
              "sectionSummary": "80到160字概括本节关键事实",
              "continuityNotes": ["连续性事实"],
              "openLoops": ["未解决伏笔"],
              "resolvedLoops": ["已回收伏笔"]
            }

            重写硬规则：
            - 保留原小节应该完成的核心事件、人物关系和因果方向，不要重启故事。
            - 必须修复质检问题，不要只替换同义词。
            - 删除空泛套话，改用动作、对话、具体物件、数字、证据和后果推动剧情。
            - 本节目标长度约 %d 个中文字符。
            - %s

            质检分数：%d
            质检问题 JSON：%s
            重写建议 JSON：%s

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

            原小节 JSON：
            %s
            """.formatted(
            sectionIndex,
            totalSections,
            targetChars,
            finalSection ? "这是最后一节，必须收束主线，不要新增未铺垫的新主线钩子。" : "结尾保留自然钩子，但不要机械断章。",
            qualityReport == null ? 0 : qualityReport.getScore(),
            toJson(qualityReport == null ? List.of() : qualityReport.getIssues()),
            toJson(qualityReport == null ? List.of() : qualityReport.getRewriteAdvice()),
            toJson(promptPlanView(plan)),
            toJson(sectionPlan),
            storySoFar == null || storySoFar.isBlank() ? "无" : storySoFar,
            toJson(continuityNotes == null ? List.of() : continuityNotes),
            toJson(openLoops == null ? List.of() : openLoops),
            previousEnding == null || previousEnding.isBlank() ? "无" : previousEnding,
            toJson(originalDraft)
    );
}

public String buildFinalEditPrompt(
        StoryTopicPlan plan,
        List<StorySectionDraft> drafts,
        int actualCharCount
) {
    return """
            你是中文短篇网文终审编辑。请对整篇小说做最终编辑润色。
            只返回一个 JSON object，不要输出 markdown、注释或额外解释。

            必须返回的 JSON schema：
            {
              "editedSections": [
                {
                  "section": 1,
                  "sectionTitle": "小节标题",
                  "content": "润色后的正文",
                  "sectionSummary": "80到160字本节摘要"
                }
              ],
              "editorNotes": ["做过的关键编辑说明"]
            }

            编辑规则：
            - 不能改变核心事件、人物动机、伏笔回收和结局方向。
            - 删除 AI 套话、空泛总结、机械升级和重复钩子。
            - 补强动作、对话、可感知细节、数字、证据和因果结果。
            - 最后一节必须收束主线，不能新增未铺垫的新主线。
            - 保持 section 数量和 section 编号不变。
            - 当前全文非空白字数约 %d。

            整体规划 JSON：
            %s

            待编辑章节 JSON：
            %s
            """.formatted(
            actualCharCount,
            toJson(promptPlanView(plan)),
            toJson(drafts == null ? List.of() : drafts)
    );
}
```

Also update the topic prompt rule section to require multiple candidate evaluation in natural language:

```java
- 先在内部构思至少 3 个候选题材，并按“榜单新鲜度、开篇压迫、付费/追读期待、原创性、1.5 万字内完成度”筛选；最终 JSON 只输出被选中的一个完整方案。
```

- [ ] **Step 4: Run prompt tests and verify GREEN**

Run:

```powershell
cd backend
.\mvnw -Dtest=AiStoryPromptBuilderTest test
```

Expected: all prompt builder tests pass.

- [ ] **Step 5: Commit Task 2**

```powershell
git add backend/src/main/java/com/trade/story/decision/AiStoryPromptBuilder.java backend/src/test/java/com/trade/story/decision/AiStoryPromptBuilderTest.java
git commit -m "feat: add story rewrite prompts"
```

---

### Task 3: Final Editor Response Parser

**Files:**
- Modify: `backend/src/main/java/com/trade/story/decision/AiStoryResponseParser.java`
- Test: `backend/src/test/java/com/trade/story/decision/AiStoryResponseParserTest.java`

- [ ] **Step 1: Write failing parser test**

Add this test:

```java
@Test
void parsesFinalEditResponse() {
    List<StorySectionDraft> drafts = parser.parseFinalEditResponse("""
            {
              "editedSections": [
                {
                  "section": 1,
                  "sectionTitle": "雨线",
                  "content": "正文：\\n房东把欠条拍在桌上。林舟反手拨通电话。",
                  "sectionSummary": "林舟被催债后主动追查证据。"
                },
                {
                  "section": 2,
                  "sectionTitle": "反击",
                  "content": "王经理看完监控，手里的茶杯磕在桌沿。",
                  "sectionSummary": "王经理被证据反压。"
                }
              ],
              "editorNotes": ["删除套话", "补强动作"]
            }
            """);

    assertEquals(2, drafts.size());
    assertEquals(1, drafts.get(0).getSection());
    assertEquals("雨线", drafts.get(0).getSectionTitle());
    assertEquals("房东把欠条拍在桌上。林舟反手拨通电话。", drafts.get(0).getContent());
    assertEquals("王经理被证据反压。", drafts.get(1).getSectionSummary());
}
```

- [ ] **Step 2: Run parser tests and verify RED**

Run:

```powershell
cd backend
.\mvnw -Dtest=AiStoryResponseParserTest test
```

Expected: compilation failure because `parseFinalEditResponse` does not exist.

- [ ] **Step 3: Implement final edit parser**

Add imports:

```java
import java.util.Comparator;
```

Add method:

```java
public List<StorySectionDraft> parseFinalEditResponse(String rawResponse) {
    if (rawResponse == null || rawResponse.isBlank()) {
        throw new IllegalArgumentException("AI story final edit response is empty");
    }

    try {
        JsonNode root = objectMapper.readTree(extractJsonObject(rawResponse));
        JsonNode editedSections = firstExisting(root, "editedSections", "sections");
        if (editedSections == null || !editedSections.isArray() || editedSections.isEmpty()) {
            throw new IllegalArgumentException("AI story final edit response missing editedSections");
        }
        List<StorySectionDraft> drafts = new ArrayList<>();
        int fallbackSection = 1;
        for (JsonNode item : editedSections) {
            if (item == null || item.isNull()) {
                continue;
            }
            String content = firstText(item, "content", "正文", "story", "text");
            if (!hasText(content)) {
                throw new IllegalArgumentException("AI story final edit section missing content");
            }
            int section = intOrDefault(item.path("section"), fallbackSection);
            drafts.add(new StorySectionDraft()
                    .setSection(section)
                    .setSectionTitle(firstText(item, "sectionTitle", "chapterTitle", "title"))
                    .setContent(normalizeContent(content))
                    .setSectionSummary(firstText(item, "sectionSummary", "summary", "本节摘要"))
                    .setRawResponse(rawResponse));
            fallbackSection++;
        }
        drafts.sort(Comparator.comparingInt(StorySectionDraft::getSection));
        return drafts;
    } catch (Exception e) {
        throw new IllegalArgumentException("Parse AI story final edit response failed: " + e.getMessage(), e);
    }
}
```

- [ ] **Step 4: Run parser tests and verify GREEN**

Run:

```powershell
cd backend
.\mvnw -Dtest=AiStoryResponseParserTest test
```

Expected: all parser tests pass.

- [ ] **Step 5: Commit Task 3**

```powershell
git add backend/src/main/java/com/trade/story/decision/AiStoryResponseParser.java backend/src/test/java/com/trade/story/decision/AiStoryResponseParserTest.java
git commit -m "feat: parse story final edits"
```

---

### Task 4: Service Rewrite And Final Edit Orchestration

**Files:**
- Modify: `backend/src/main/java/com/trade/story/application/AiStoryService.java`
- Test: `backend/src/test/java/com/trade/story/application/AiStoryServiceTest.java`

- [ ] **Step 1: Create failing service orchestration test**

Create `AiStoryServiceTest`:

```java
package com.trade.story.application;

import com.trade.ai.persistence.AiResponseParseErrorSink;
import com.trade.client.ai.AiTextClient;
import com.trade.story.config.AiStoryProperties;
import com.trade.story.decision.AiStoryPromptBuilder;
import com.trade.story.decision.AiStoryResponseParser;
import com.trade.story.model.StoryGenerationResult;
import com.trade.story.model.StorySectionDraft;
import com.trade.story.model.StoryTopicPlan;
import com.trade.story.model.StoryTrendContext;
import com.trade.story.persistence.StoryFileRepository;
import com.trade.story.trend.StoryTrendCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStoryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void rewritesFailedSectionAndRunsFinalEditorPass() {
        AiStoryProperties properties = new AiStoryProperties();
        properties.setEnabled(true);
        properties.setOutputDir(tempDir.toString());
        properties.setTargetCharCount(4_000);
        properties.setMinAcceptableCharCount(1);
        properties.setSectionCount(2);
        properties.setMaxContinuationSections(0);
        properties.setMaxSectionRewriteAttempts(1);
        properties.setFinalEditorPassEnabled(true);

        FakeAiTextClient ai = new FakeAiTextClient();
        ai.enqueue(topicPlanJson());
        ai.enqueue(sectionJson(1, "雨线", "命运的齿轮开始转动，全场震惊。林舟嘴角勾起一抹弧度，空气凝固了。", "开篇只有套话。"));
        ai.enqueue(sectionJson(1, "雨线", """
                房东把欠条拍在桌上：“今晚十二点前，三千二，一分不能少。”
                林舟没有解释，先把外卖箱压住门缝，反手拨通旧货市场老板的电话。
                老板发来监控，王经理的司机把纸箱放到摊边，转身就走。
                林舟把视频转给苏晴，又把欠条折进钱包。半小时后，王经理主动打电话，声音发虚。
                林舟报了个数字：“三万二，今晚十二点前，一分不能少。”
                """, "林舟被催债后主动追查青花碗来源，用监控证据反压王经理。"));
        ai.enqueue(sectionJson(2, "赔偿", """
                王经理看完监控，手里的茶杯磕在桌沿。
                林舟把录音笔放到桌上，先按下播放键，再把赔偿协议推过去。
                “签，还是我把视频交给警方？”他说。
                王经理盯着门外的员工，最终写下名字。赔偿到账时，林舟把房东的欠款一次结清。
                """, "林舟用监控和录音逼王经理签赔偿协议，解决欠款并完成第一轮反击。"));
        ai.enqueue("""
                {
                  "editedSections": [
                    {
                      "section": 1,
                      "sectionTitle": "雨线",
                      "content": "房东把欠条拍在桌上。林舟反手拨通电话，拿到监控证据。",
                      "sectionSummary": "林舟被催债后主动追查青花碗来源，用监控证据反压王经理。"
                    },
                    {
                      "section": 2,
                      "sectionTitle": "赔偿",
                      "content": "王经理看完监控后签下赔偿协议，林舟结清欠款。",
                      "sectionSummary": "林舟用证据逼王经理赔偿，解决欠款并完成反击。"
                    }
                  ],
                  "editorNotes": ["压缩套话", "保留核心事件"]
                }
                """);

        RecordingStoryFileRepository repository = new RecordingStoryFileRepository(properties, tempDir.resolve("story.txt"));
        AiStoryService service = new AiStoryService(
                ai,
                new FixedTrendCollector(properties),
                new AiStoryPromptBuilder(),
                new AiStoryResponseParser(),
                repository,
                properties,
                AiResponseParseErrorSink.NOOP
        );

        Optional<StoryGenerationResult> result = service.generateStory();

        assertTrue(result.isPresent());
        assertTrue(ai.prompts.stream().anyMatch(prompt -> prompt.contains("质检问题")));
        assertTrue(ai.prompts.stream().anyMatch(prompt -> prompt.contains("最终编辑润色")));
        assertEquals("房东把欠条拍在桌上。林舟反手拨通电话，拿到监控证据。", repository.savedDrafts.get(0).getContent());
    }

    private static String topicPlanJson() {
        return """
                {
                  "title": "雨夜规则",
                  "genre": "规则怪谈",
                  "hotTopic": "城市规则怪谈",
                  "targetAudience": "喜欢快节奏悬疑爽文的读者",
                  "premise": "林舟在债务压力下发现规则纸条并反击设局者",
                  "corePromise": "主角用证据和规则反压对手",
                  "sellingPoints": ["开局催债", "证据反击", "规则反转"],
                  "characterBible": ["林舟：外卖员，嘴硬但细心"],
                  "relationshipMap": ["林舟-王经理：压迫者与反击者"],
                  "worldRules": ["触碰旧物能看到来源片段"],
                  "plotThreads": ["青花碗是谁放到摊位上的"],
                  "outline": ["催债", "查证", "反击", "赔偿"],
                  "sectionPlan": [
                    {
                      "section": 1,
                      "title": "雨线",
                      "summary": "林舟被催债后追查证据",
                      "entryState": "林舟欠房租",
                      "keyBeats": ["房东催债", "林舟主动追查", "拿到监控"],
                      "mustPayoff": "确认王经理设局",
                      "exitState": "林舟掌握证据",
                      "cliffhanger": "王经理打来电话",
                      "targetChars": 2000
                    },
                    {
                      "section": 2,
                      "title": "赔偿",
                      "summary": "林舟用证据反压王经理",
                      "entryState": "林舟掌握证据",
                      "keyBeats": ["播放录音", "逼签赔偿", "结清欠款"],
                      "mustPayoff": "王经理赔偿",
                      "exitState": "林舟解决欠款",
                      "cliffhanger": "主线收束",
                      "targetChars": 2000
                    }
                  ],
                  "styleGuide": "短段落，强冲突",
                  "antiClicheRules": ["不要写命运的齿轮"]
                }
                """;
    }

    private static String sectionJson(int section, String title, String content, String summary) {
        return """
                {
                  "sectionTitle": "%s",
                  "content": %s,
                  "endingHook": "自然推进",
                  "sectionSummary": "%s",
                  "continuityNotes": ["第%s节事实"],
                  "openLoops": [],
                  "resolvedLoops": []
                }
                """.formatted(title, quote(content), summary, section);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private static class FakeAiTextClient implements AiTextClient {
        private final Queue<String> responses = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        void enqueue(String response) {
            responses.add(response);
        }

        @Override
        public String generateJson(String prompt) {
            prompts.add(prompt);
            String response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("No fake AI response queued for prompt: " + prompt);
            }
            return response;
        }
    }

    private static class FixedTrendCollector extends StoryTrendCollector {
        FixedTrendCollector(AiStoryProperties properties) {
            super(properties);
        }

        @Override
        public StoryTrendContext collect() {
            return new StoryTrendContext()
                    .setCollectedAt(Instant.parse("2026-06-16T00:00:00Z"))
                    .setSources(List.of("test"))
                    .setTrendText("规则怪谈：城市规则与现实压力结合");
        }
    }

    private static class RecordingStoryFileRepository extends StoryFileRepository {
        private final Path outputPath;
        private List<StorySectionDraft> savedDrafts = List.of();

        RecordingStoryFileRepository(AiStoryProperties properties, Path outputPath) {
            super(properties);
            this.outputPath = outputPath;
        }

        @Override
        public Path save(
                StoryTopicPlan plan,
                List<StorySectionDraft> drafts,
                StoryTrendContext trendContext,
                int actualCharCount
        ) {
            this.savedDrafts = new ArrayList<>(drafts);
            return outputPath;
        }

        @Override
        public List<String> recentStoryNames() {
            return List.of();
        }
    }
}
```

- [ ] **Step 2: Run service test and verify RED**

Run:

```powershell
cd backend
.\mvnw -Dtest=AiStoryServiceTest test
```

Expected: compilation failure or assertion failure because `AiStoryService` does not evaluate quality or request rewrites yet.

- [ ] **Step 3: Modify service constructor and field**

Add imports:

```java
import com.trade.story.decision.StoryQualityGate;
import com.trade.story.decision.StoryQualityReport;
```

Add field:

```java
private final StoryQualityGate qualityGate;
```

Update the seven-argument `@Autowired` constructor body so it delegates to a new eight-argument constructor:

```java
public AiStoryService(
        AiTextClient aiTextClient,
        StoryTrendCollector trendCollector,
        AiStoryPromptBuilder promptBuilder,
        AiStoryResponseParser responseParser,
        StoryFileRepository fileRepository,
        AiStoryProperties properties,
        AiResponseParseErrorSink parseErrorSink
) {
    this(
            aiTextClient,
            trendCollector,
            promptBuilder,
            responseParser,
            fileRepository,
            properties,
            parseErrorSink,
            new StoryQualityGate(properties)
    );
}

public AiStoryService(
        AiTextClient aiTextClient,
        StoryTrendCollector trendCollector,
        AiStoryPromptBuilder promptBuilder,
        AiStoryResponseParser responseParser,
        StoryFileRepository fileRepository,
        AiStoryProperties properties,
        AiResponseParseErrorSink parseErrorSink,
        StoryQualityGate qualityGate
) {
    this.aiTextClient = aiTextClient;
    this.trendCollector = trendCollector;
    this.promptBuilder = promptBuilder;
    this.responseParser = responseParser;
    this.fileRepository = fileRepository;
    this.properties = properties;
    this.parseErrorSink = parseErrorSink == null ? AiResponseParseErrorSink.NOOP : parseErrorSink;
    this.qualityGate = qualityGate == null ? new StoryQualityGate(properties) : qualityGate;
}
```

- [ ] **Step 4: Implement rewrite selection in generateSection**

After parsing `draft`, call:

```java
StoryQualityReport report = qualityGate.evaluateSection(
        draft,
        sectionIndex,
        totalSections,
        targetChars,
        finalSection
);
if (properties.isQualityGateEnabled()
        && !report.isPassed()
        && properties.getMaxSectionRewriteAttempts() > 0) {
    StorySectionDraft rewrite = rewriteSection(
            generationId,
            topicPlan,
            sectionPlan,
            draft,
            report,
            sectionIndex,
            totalSections,
            targetChars,
            storySoFar,
            continuityNotes,
            openLoops,
            previousEnding,
            finalSection
    );
    StoryQualityReport rewriteReport = qualityGate.evaluateSection(
            rewrite,
            sectionIndex,
            totalSections,
            targetChars,
            finalSection
    );
    if (rewriteReport.getScore() >= report.getScore()) {
        draft = rewrite;
        report = rewriteReport;
    }
}
```

Create helper `rewriteSection` with this signature:

```java
private StorySectionDraft rewriteSection(
        String generationId,
        StoryTopicPlan topicPlan,
        StorySectionPlan sectionPlan,
        StorySectionDraft originalDraft,
        StoryQualityReport qualityReport,
        int sectionIndex,
        int totalSections,
        int targetChars,
        String storySoFar,
        List<String> continuityNotes,
        List<String> openLoops,
        String previousEnding,
        boolean finalSection
)
```

The helper builds `buildSectionRewritePrompt`, calls `aiTextClient.generateJson`, parses with `parseSectionDraft`, persists parse errors with phase `SECTION_REWRITE_PAYLOAD`, and returns `originalDraft` on errors.

- [ ] **Step 5: Add final editor pass**

After `writeContinuationSectionsIfNeeded`, call helper:

```java
drafts = editFinalDraftIfEnabled(generationId, topicPlan, drafts);
```

Create helper `editFinalDraftIfEnabled` with this signature:

```java
private List<StorySectionDraft> editFinalDraftIfEnabled(
        String generationId,
        StoryTopicPlan topicPlan,
        List<StorySectionDraft> drafts
)
```

The helper:

- Return drafts unchanged when `finalEditorPassEnabled` is false or drafts empty.
- Build `buildFinalEditPrompt`.
- Call AI.
- Parse `parseFinalEditResponse`.
- Keep edited drafts only when section count equals original draft count.
- Persist parse errors with phase `FINAL_EDIT_PAYLOAD`.
- On any exception, log warning and return original drafts.

Because `writeContinuationSectionsIfNeeded` mutates the list, change the service code to assign:

```java
List<StorySectionDraft> drafts = writePlannedSections(generationId, topicPlan);
writeContinuationSectionsIfNeeded(generationId, topicPlan, drafts);
drafts = editFinalDraftIfEnabled(generationId, topicPlan, drafts);
```

- [ ] **Step 6: Run service test and verify GREEN**

Run:

```powershell
cd backend
.\mvnw -Dtest=AiStoryServiceTest test
```

Expected: service tests pass and fake AI prompt list contains rewrite/editor prompts.

- [ ] **Step 7: Commit Task 4**

```powershell
git add backend/src/main/java/com/trade/story/application/AiStoryService.java backend/src/test/java/com/trade/story/application/AiStoryServiceTest.java
git commit -m "feat: rewrite low quality story sections"
```

---

### Task 5: Focused Regression And Full Verification

**Files:**
- No production file changes expected.

- [ ] **Step 1: Run focused story tests**

Run:

```powershell
cd backend
.\mvnw -Dtest=StoryQualityGateTest,AiStoryPromptBuilderTest,AiStoryResponseParserTest,AiStoryServiceTest test
```

Expected: all focused story tests pass.

- [ ] **Step 2: Run full backend tests**

Run:

```powershell
cd backend
.\mvnw test
```

Expected: build success with all tests passing, except existing skipped integration tests.

- [ ] **Step 3: Inspect git diff**

Run:

```powershell
git status --short
git diff --stat
```

Expected: only story implementation, story tests, and plan docs are changed in this worktree.

- [ ] **Step 4: Finish branch**

Report the branch, worktree path, test command results, and changed files. Do not merge back into `main` unless explicitly requested.
