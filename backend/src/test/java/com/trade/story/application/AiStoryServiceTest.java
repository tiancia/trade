package com.trade.story.application;

import com.trade.ai.port.AiResponseParseErrorSink;
import com.trade.ai.domain.model.AiResponseParseErrorRecord;
import com.trade.client.ai.AiTextClient;
import com.trade.story.application.decision.AiStoryPromptBuilder;
import com.trade.story.application.decision.AiStoryResponseParser;
import com.trade.story.application.service.AiStoryService;
import com.trade.story.domain.model.StoryGenerationResult;
import com.trade.story.domain.model.StorySectionDraft;
import com.trade.story.domain.model.StoryTopicPlan;
import com.trade.story.domain.model.StoryTrendContext;
import com.trade.story.infrastructure.config.AiStoryProperties;
import com.trade.story.infrastructure.persistence.StoryFileRepository;
import com.trade.story.infrastructure.trend.StoryTrendCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiStoryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsUnwritableOutputBeforeAnyAiRequest() throws Exception {
        AiStoryProperties properties = new AiStoryProperties();
        properties.setEnabled(true);
        Path blocked = tempDir.resolve("existing-file");
        Files.writeString(blocked, "not a directory");
        properties.setOutputDir(blocked.toString());
        FakeAiTextClient ai = new FakeAiTextClient();
        var service = new AiStoryService(ai, new FixedTrendCollector(properties), new AiStoryPromptBuilder(),
                new AiStoryResponseParser(), new StoryFileRepository(properties), properties);
        assertThrows(IllegalStateException.class, service::generateStory);
        assertTrue(ai.prompts.isEmpty());
    }

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
        assertEquals(5, ai.prompts.size());
    }

    @Test
    void keepsOriginalDraftAndRecordsParseErrorsWhenOptionalEditsAreInvalid() {
        AiStoryProperties properties = new AiStoryProperties();
        properties.setEnabled(true);
        properties.setOutputDir(tempDir.toString());
        properties.setTargetCharCount(2_000);
        properties.setMinAcceptableCharCount(1);
        properties.setSectionCount(1);
        properties.setMaxContinuationSections(0);
        properties.setMaxSectionRewriteAttempts(1);
        properties.setFinalEditorPassEnabled(true);

        String originalContent = "命运的齿轮开始转动，全场震惊。林舟嘴角勾起一抹弧度，空气凝固了。";
        FakeAiTextClient ai = new FakeAiTextClient();
        ai.enqueue(topicPlanJson());
        ai.enqueue(sectionJson(1, "雨线", originalContent, "原始稿件。"));
        ai.enqueue("{\"sectionTitle\":\"雨线\"}");
        ai.enqueue("{\"editedSections\":[]}");
        List<AiResponseParseErrorRecord> parseErrors = new ArrayList<>();
        RecordingStoryFileRepository repository = new RecordingStoryFileRepository(properties, tempDir.resolve("story.txt"));
        AiStoryService service = new AiStoryService(
                ai,
                new FixedTrendCollector(properties),
                new AiStoryPromptBuilder(),
                new AiStoryResponseParser(),
                repository,
                properties,
                parseErrors::add
        );

        Optional<StoryGenerationResult> result = service.generateStory();

        assertTrue(result.isPresent());
        assertEquals(1, repository.savedDrafts.size());
        assertEquals(originalContent, repository.savedDrafts.get(0).getContent());
        assertTrue(result.orElseThrow().isDegraded());
        assertEquals(List.of("SECTION_REWRITE_PAYLOAD", "FINAL_EDIT_PAYLOAD"),
                parseErrors.stream().map(AiResponseParseErrorRecord::getPhase).toList());
        assertEquals(List.of("KEEP_ORIGINAL_SECTION_DRAFT", "KEEP_PRE_EDIT_DRAFTS"),
                parseErrors.stream().map(AiResponseParseErrorRecord::getFallbackAction).toList());
        assertEquals(4, ai.prompts.size());
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
