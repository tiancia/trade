package com.trade.story.application;

import com.trade.ai.audit.AiResponseParseErrorRecord;
import com.trade.ai.audit.AiResponseParseErrorSink;
import com.trade.client.ai.AiResponseParseException;
import com.trade.client.ai.AiTextClient;
import com.trade.story.config.AiStoryProperties;
import com.trade.story.decision.AiStoryPromptBuilder;
import com.trade.story.decision.AiStoryResponseParser;
import com.trade.story.model.StoryGenerationResult;
import com.trade.story.model.StorySectionDraft;
import com.trade.story.model.StorySectionPlan;
import com.trade.story.model.StoryTopicPlan;
import com.trade.story.model.StoryTrendContext;
import com.trade.story.persistence.StoryFileRepository;
import com.trade.story.trend.StoryTrendCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Generates one complete story artifact from trend collection through section
 * drafting and file persistence.
 */
@Component
public class AiStoryService {
    private static final Logger log = LoggerFactory.getLogger(AiStoryService.class);

    private final AiTextClient aiTextClient;
    private final StoryTrendCollector trendCollector;
    private final AiStoryPromptBuilder promptBuilder;
    private final AiStoryResponseParser responseParser;
    private final StoryFileRepository fileRepository;
    private final AiStoryProperties properties;
    private final AiResponseParseErrorSink parseErrorSink;
    // Story generation is long-running and writes files, so overlapping runs
    // are skipped instead of queued.
    private final ReentrantLock generationLock = new ReentrantLock();

    public AiStoryService(
            AiTextClient aiTextClient,
            StoryTrendCollector trendCollector,
            AiStoryPromptBuilder promptBuilder,
            AiStoryResponseParser responseParser,
            StoryFileRepository fileRepository,
            AiStoryProperties properties
    ) {
        this(
                aiTextClient,
                trendCollector,
                promptBuilder,
                responseParser,
                fileRepository,
                properties,
                AiResponseParseErrorSink.NOOP
        );
    }

    @Autowired
    public AiStoryService(
            AiTextClient aiTextClient,
            StoryTrendCollector trendCollector,
            AiStoryPromptBuilder promptBuilder,
            AiStoryResponseParser responseParser,
            StoryFileRepository fileRepository,
            AiStoryProperties properties,
            AiResponseParseErrorSink parseErrorSink
    ) {
        this.aiTextClient = aiTextClient;
        this.trendCollector = trendCollector;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.fileRepository = fileRepository;
        this.properties = properties;
        this.parseErrorSink = parseErrorSink == null ? AiResponseParseErrorSink.NOOP : parseErrorSink;
    }

    public Optional<StoryGenerationResult> generateStory() {
        if (!properties.isEnabled()) {
            log.info("AI story module is disabled");
            return Optional.empty();
        }
        if (!generationLock.tryLock()) {
            log.info("AI story generation is already running");
            return Optional.empty();
        }

        String generationId = UUID.randomUUID().toString();
        long startedAtMillis = System.currentTimeMillis();
        try {
            log.info(
                    "AI story generation started: generationId={}, targetCharCount={}, sectionCount={}, outputDir={}",
                    generationId,
                    properties.getTargetCharCount(),
                    properties.getSectionCount(),
                    properties.getOutputDir()
            );

            // Topic planning establishes the continuity bible used by every
            // section prompt that follows.
            StoryTrendContext trendContext = trendCollector.collect();
            List<String> recentStoryNames = fileRepository.recentStoryNames();
            String topicPrompt = promptBuilder.buildTopicPrompt(trendContext, recentStoryNames, properties);
            String rawTopicResponse = null;
            try {
                rawTopicResponse = aiTextClient.generateJson(topicPrompt);
            } catch (AiResponseParseException e) {
                rawTopicResponse = e.getRawResponse();
                persistAiResponseParseError(
                        generationId,
                        "TOPIC_AI_CLIENT_RESPONSE",
                        topicPrompt,
                        rawTopicResponse,
                        e.getMessage(),
                        null
                );
                throw e;
            }
            StoryTopicPlan topicPlan;
            try {
                topicPlan = responseParser.parseTopicPlan(rawTopicResponse);
            } catch (IllegalArgumentException e) {
                persistAiResponseParseError(
                        generationId,
                        "TOPIC_PAYLOAD",
                        topicPrompt,
                        rawTopicResponse,
                        e.getMessage(),
                        "FALLBACK_TOPIC_PLAN"
                );
                log.warn(
                        "AI story topic response invalid, using fallback topic plan: generationId={}, error={}",
                        generationId,
                        e.getMessage()
                );
                topicPlan = fallbackTopicPlan(rawTopicResponse, trendContext, generationId);
            }
            topicPlan.setSectionPlans(normalizedSectionPlans(topicPlan));
            log.info(
                    "AI story topic selected: generationId={}, title={}, hotTopic={}, genre={}",
                    generationId,
                    topicPlan.getTitle(),
                    topicPlan.getHotTopic(),
                    topicPlan.getGenre()
            );

            List<StorySectionDraft> drafts = writePlannedSections(generationId, topicPlan);
            writeContinuationSectionsIfNeeded(generationId, topicPlan, drafts);

            int actualCharCount = countNonWhitespace(joinContents(drafts));
            Path outputPath = fileRepository.save(topicPlan, drafts, trendContext, actualCharCount);
            StoryGenerationResult result = new StoryGenerationResult()
                    .setGenerationId(generationId)
                    .setTitle(topicPlan.getTitle())
                    .setHotTopic(topicPlan.getHotTopic())
                    .setOutputPath(outputPath)
                    .setGeneratedAt(Instant.now())
                    .setSectionCount(drafts.size())
                    .setTargetCharCount(properties.getTargetCharCount())
                    .setActualCharCount(actualCharCount);

            log.info(
                    "AI story generation finished: generationId={}, title={}, chars={}, outputPath={}, elapsedMs={}",
                    generationId,
                    result.getTitle(),
                    result.getActualCharCount(),
                    result.getOutputPath(),
                    System.currentTimeMillis() - startedAtMillis
            );
            return Optional.of(result);
        } catch (Exception e) {
            log.error(
                    "AI story generation failed: generationId={}, elapsedMs={}, error={}",
                    generationId,
                    System.currentTimeMillis() - startedAtMillis,
                    e.getMessage(),
                    e
            );
            return Optional.empty();
        } finally {
            generationLock.unlock();
        }
    }

    private List<StorySectionDraft> writePlannedSections(String generationId, StoryTopicPlan topicPlan) {
        List<StorySectionDraft> drafts = new ArrayList<>();
        int totalSections = topicPlan.getSectionPlans().size();
        for (int i = 0; i < totalSections; i++) {
            // Recompute written text before every prompt so each section can
            // account for actual prior output length, not only the plan.
            StorySectionPlan sectionPlan = topicPlan.getSectionPlans().get(i);
            int writtenChars = countNonWhitespace(joinContents(drafts));
            int remainingSections = totalSections - i;
            int targetChars = sectionTargetChars(writtenChars, remainingSections, sectionPlan.getTargetChars());
            String writtenText = joinContents(drafts);
            StorySectionDraft draft = generateSection(
                    generationId,
                    topicPlan,
                    sectionPlan,
                    i + 1,
                    totalSections,
                    targetChars,
                    writtenChars,
                    storySoFar(drafts),
                    continuityNotes(drafts),
                    activeOpenLoops(drafts),
                    tail(writtenText, 1_800),
                    i + 1 == totalSections
            );
            drafts.add(draft);
        }
        return drafts;
    }

    private void writeContinuationSectionsIfNeeded(
            String generationId,
            StoryTopicPlan topicPlan,
            List<StorySectionDraft> drafts
    ) {
        int continuation = 0;
        while (countNonWhitespace(joinContents(drafts)) < properties.getMinAcceptableCharCount()
                && continuation < properties.getMaxContinuationSections()) {
            int sectionNumber = drafts.size() + 1;
            int writtenChars = countNonWhitespace(joinContents(drafts));
            int targetChars = clamp(properties.getTargetCharCount() - writtenChars, 1_800, 3_500);
            StorySectionPlan sectionPlan = new StorySectionPlan()
                    .setSection(sectionNumber)
                    .setTitle("补充收束")
                    .setSummary("在不拖沓的前提下补足关键情节、情绪回收和结局余味。")
                    .setEntryState("承接前文最新状态，优先处理未回收伏笔。")
                    .setKeyBeats(List.of("回收最重要的未解决矛盾", "让主角主动完成最后选择", "给核心人物关系和收益一个清楚结果"))
                    .setMustPayoff("补足前文承诺过但尚未兑现的爽点和情绪回收。")
                    .setExitState("主线完成，人物去向清楚，不留下影响完整性的断章。")
                    .setCliffhanger("只保留轻微余味，不再开启新主线。")
                    .setTargetChars(targetChars);
            String writtenText = joinContents(drafts);
            drafts.add(generateSection(
                    generationId,
                    topicPlan,
                    sectionPlan,
                    sectionNumber,
                    sectionNumber,
                    targetChars,
                    writtenChars,
                    storySoFar(drafts),
                    continuityNotes(drafts),
                    activeOpenLoops(drafts),
                    tail(writtenText, 1_800),
                    true
            ));
            continuation++;
        }
    }

    private StorySectionDraft generateSection(
            String generationId,
            StoryTopicPlan topicPlan,
            StorySectionPlan sectionPlan,
            int sectionIndex,
            int totalSections,
            int targetChars,
            int writtenChars,
            String storySoFar,
            List<String> continuityNotes,
            List<String> openLoops,
            String previousEnding,
            boolean finalSection
    ) {
        log.info(
                "AI story section request started: generationId={}, section={}, targetChars={}, writtenChars={}",
                generationId,
                sectionIndex,
                targetChars,
                writtenChars
        );
        String prompt = promptBuilder.buildSectionPrompt(
                topicPlan,
                sectionPlan,
                sectionIndex,
                totalSections,
                targetChars,
                properties.getTargetCharCount(),
                writtenChars,
                storySoFar,
                continuityNotes,
                openLoops,
                previousEnding,
                finalSection
        );
        String rawResponse = null;
        try {
            rawResponse = aiTextClient.generateJson(prompt);
        } catch (AiResponseParseException e) {
            rawResponse = e.getRawResponse();
            persistAiResponseParseError(
                    generationId,
                    "SECTION_AI_CLIENT_RESPONSE",
                    prompt,
                    rawResponse,
                    e.getMessage(),
                    null
            );
            throw e;
        }
        StorySectionDraft draft;
        try {
            draft = responseParser.parseSectionDraft(rawResponse, sectionIndex);
        } catch (IllegalArgumentException e) {
            persistAiResponseParseError(
                    generationId,
                    "SECTION_PAYLOAD",
                    prompt,
                    rawResponse,
                    e.getMessage(),
                    "FALLBACK_SECTION_DRAFT"
            );
            log.warn(
                    "AI story section response invalid, using fallback section draft: generationId={}, section={}, error={}",
                    generationId,
                    sectionIndex,
                    e.getMessage()
            );
            draft = fallbackSectionDraft(rawResponse, sectionIndex, sectionPlan);
        }
        if (!hasText(draft.getSectionSummary())) {
            draft.setSectionSummary(briefFromContent(draft.getContent(), 180));
        }
        int actualChars = countNonWhitespace(draft.getContent());
        log.info(
                "AI story section generated: generationId={}, section={}, title={}, chars={}",
                generationId,
                sectionIndex,
                draft.getSectionTitle(),
                actualChars
        );
        return draft;
    }

    private StoryTopicPlan fallbackTopicPlan(
            String rawResponse,
            StoryTrendContext trendContext,
            String generationId
    ) {
        int sectionCount = Math.max(1, properties.getSectionCount());
        int defaultTarget = Math.max(1, properties.getTargetCharCount() / sectionCount);
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

    private StorySectionDraft fallbackSectionDraft(
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

    private List<StorySectionPlan> normalizedSectionPlans(StoryTopicPlan topicPlan) {
        int sectionCount = Math.max(1, properties.getSectionCount());
        int defaultTarget = Math.max(1, properties.getTargetCharCount() / sectionCount);
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

    private int sectionTargetChars(int writtenChars, int remainingSections, int plannedTargetChars) {
        int remainingTarget = Math.max(1_500, properties.getTargetCharCount() - writtenChars);
        int balancedTarget = remainingTarget / Math.max(1, remainingSections);
        int target = plannedTargetChars > 0 ? (plannedTargetChars + balancedTarget) / 2 : balancedTarget;
        return clamp(target, 1_800, 3_500);
    }

    private static String joinContents(List<StorySectionDraft> drafts) {
        StringBuilder text = new StringBuilder();
        for (StorySectionDraft draft : drafts) {
            if (draft.getContent() != null) {
                text.append(draft.getContent()).append('\n');
            }
        }
        return text.toString();
    }

    private static String storySoFar(List<StorySectionDraft> drafts) {
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

    private static List<String> continuityNotes(List<StorySectionDraft> drafts) {
        Set<String> values = new LinkedHashSet<>();
        if (drafts != null) {
            for (StorySectionDraft draft : drafts) {
                addAll(values, draft == null ? null : draft.getContinuityNotes());
            }
        }
        return values.stream().limit(32).toList();
    }

    private static List<String> activeOpenLoops(List<StorySectionDraft> drafts) {
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

    private static void addAll(Set<String> target, List<String> values) {
        for (String value : nullSafe(values)) {
            if (hasText(value)) {
                target.add(value.trim());
            }
        }
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String briefFromContent(String value, int maxChars) {
        if (!hasText(value)) {
            return "";
        }
        String text = value.replaceAll("\\s+", "");
        return limit(text, maxChars);
    }

    private static String tail(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(value.length() - maxChars);
    }

    private static int countNonWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return (int) value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .count();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)) + "...";
    }

    private void persistAiResponseParseError(
            String generationId,
            String phase,
            String prompt,
            String rawResponse,
            String errorMessage,
            String fallbackAction
    ) {
        try {
            parseErrorSink.save(new AiResponseParseErrorRecord()
                    .setSource("STORY")
                    .setPhase(phase)
                    .setRelatedId(generationId)
                    .setPromptText(prompt)
                    .setRawResponse(rawResponse)
                    .setErrorMessage(errorMessage)
                    .setFallbackAction(fallbackAction));
        } catch (Exception e) {
            log.warn("Persist story AI response parse error failed: {}", e.getMessage(), e);
        }
    }

    private static String firstTrendLine(StoryTrendContext trendContext) {
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

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
