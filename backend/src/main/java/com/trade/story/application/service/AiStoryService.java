package com.trade.story.application.service;

import static com.trade.story.domain.rule.StoryDraftPolicy.hasText;
import static com.trade.story.domain.rule.StoryDraftPolicy.countNonWhitespace;
import static com.trade.story.domain.rule.StoryDraftPolicy.tail;
import static com.trade.story.domain.rule.StoryDraftPolicy.briefFromContent;
import static com.trade.story.domain.rule.StoryDraftPolicy.activeOpenLoops;
import static com.trade.story.domain.rule.StoryDraftPolicy.continuityNotes;
import static com.trade.story.domain.rule.StoryDraftPolicy.storySoFar;
import static com.trade.story.domain.rule.StoryDraftPolicy.joinContents;
import com.trade.story.domain.rule.StoryDraftPolicy;
import com.trade.ai.application.port.AiResponseParseErrorSink;
import com.trade.ai.domain.model.AiResponseParseErrorRecord;
import com.trade.client.ai.AiResponseParseException;
import com.trade.client.ai.AiTextClient;
import com.trade.story.application.decision.AiStoryPromptBuilder;
import com.trade.story.application.decision.AiStoryResponseParser;
import com.trade.story.application.decision.StoryQualityGate;
import com.trade.story.application.decision.StoryQualityReport;
import com.trade.story.domain.model.StoryGenerationResult;
import com.trade.story.domain.model.StorySectionDraft;
import com.trade.story.domain.model.StorySectionPlan;
import com.trade.story.domain.model.StoryTopicPlan;
import com.trade.story.domain.model.StoryTrendContext;
import com.trade.story.infrastructure.config.AiStoryProperties;
import com.trade.story.infrastructure.persistence.StoryFileRepository;
import com.trade.story.infrastructure.trend.StoryTrendCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final StoryQualityGate qualityGate;
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
                topicPlan = draftPolicy().fallbackTopicPlan(rawTopicResponse, trendContext, generationId);
            }
            topicPlan.setSectionPlans(draftPolicy().normalizedSectionPlans(topicPlan));
            log.info(
                    "AI story topic selected: generationId={}, title={}, hotTopic={}, genre={}",
                    generationId,
                    topicPlan.getTitle(),
                    topicPlan.getHotTopic(),
                    topicPlan.getGenre()
            );

            List<StorySectionDraft> drafts = writePlannedSections(generationId, topicPlan);
            writeContinuationSectionsIfNeeded(generationId, topicPlan, drafts);
            drafts = editFinalDraftIfEnabled(generationId, topicPlan, drafts);

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
            int targetChars = draftPolicy().sectionTargetChars(writtenChars, remainingSections, sectionPlan.getTargetChars());
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
        while (StoryDraftPolicy.shouldContinue(countNonWhitespace(joinContents(drafts)), properties.getMinAcceptableCharCount(),
                continuation, properties.getMaxContinuationSections())) {
            int sectionNumber = drafts.size() + 1;
            int writtenChars = countNonWhitespace(joinContents(drafts));
            int targetChars = draftPolicy().continuationTargetChars(writtenChars);
            StorySectionPlan sectionPlan = StoryDraftPolicy.continuationPlan(sectionNumber, targetChars);
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
            draft = draftPolicy().fallbackSectionDraft(rawResponse, sectionIndex, sectionPlan);
        }
        if (!hasText(draft.getSectionSummary())) {
            draft.setSectionSummary(briefFromContent(draft.getContent(), 180));
        }
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
        if (!report.isPassed()) {
            log.warn(
                    "AI story section quality gate not passed: generationId={}, section={}, score={}, issues={}",
                    generationId,
                    sectionIndex,
                    report.getScore(),
                    report.getIssues()
            );
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
    ) {
        String prompt = promptBuilder.buildSectionRewritePrompt(
                topicPlan,
                sectionPlan,
                originalDraft,
                qualityReport,
                sectionIndex,
                totalSections,
                targetChars,
                storySoFar,
                continuityNotes,
                openLoops,
                previousEnding,
                finalSection
        );
        String rawResponse = null;
        try {
            rawResponse = aiTextClient.generateJson(prompt);
            StorySectionDraft rewrite = responseParser.parseSectionDraft(rawResponse, sectionIndex);
            if (!hasText(rewrite.getSectionSummary())) {
                rewrite.setSectionSummary(briefFromContent(rewrite.getContent(), 180));
            }
            log.info(
                    "AI story section rewrite generated: generationId={}, section={}, chars={}",
                    generationId,
                    sectionIndex,
                    countNonWhitespace(rewrite.getContent())
            );
            return rewrite;
        } catch (AiResponseParseException e) {
            rawResponse = e.getRawResponse();
            persistAiResponseParseError(
                    generationId,
                    "SECTION_REWRITE_AI_CLIENT_RESPONSE",
                    prompt,
                    rawResponse,
                    e.getMessage(),
                    "KEEP_ORIGINAL_SECTION_DRAFT"
            );
        } catch (IllegalArgumentException e) {
            persistAiResponseParseError(
                    generationId,
                    "SECTION_REWRITE_PAYLOAD",
                    prompt,
                    rawResponse,
                    e.getMessage(),
                    "KEEP_ORIGINAL_SECTION_DRAFT"
            );
        } catch (Exception e) {
            log.warn(
                    "AI story section rewrite failed: generationId={}, section={}, error={}",
                    generationId,
                    sectionIndex,
                    e.getMessage(),
                    e
            );
        }
        return originalDraft;
    }

    private List<StorySectionDraft> editFinalDraftIfEnabled(
            String generationId,
            StoryTopicPlan topicPlan,
            List<StorySectionDraft> drafts
    ) {
        if (!properties.isFinalEditorPassEnabled() || drafts == null || drafts.isEmpty()) {
            return drafts;
        }
        String prompt = promptBuilder.buildFinalEditPrompt(
                topicPlan,
                drafts,
                countNonWhitespace(joinContents(drafts))
        );
        String rawResponse = null;
        try {
            rawResponse = aiTextClient.generateJson(prompt);
            List<StorySectionDraft> edited = responseParser.parseFinalEditResponse(rawResponse);
            if (edited.size() != drafts.size()) {
                log.warn(
                        "AI story final edit section count mismatch: generationId={}, original={}, edited={}",
                        generationId,
                        drafts.size(),
                        edited.size()
                );
                return drafts;
            }
            log.info("AI story final edit applied: generationId={}, sectionCount={}", generationId, edited.size());
            return edited;
        } catch (AiResponseParseException e) {
            rawResponse = e.getRawResponse();
            persistAiResponseParseError(
                    generationId,
                    "FINAL_EDIT_AI_CLIENT_RESPONSE",
                    prompt,
                    rawResponse,
                    e.getMessage(),
                    "KEEP_PRE_EDIT_DRAFTS"
            );
        } catch (IllegalArgumentException e) {
            persistAiResponseParseError(
                    generationId,
                    "FINAL_EDIT_PAYLOAD",
                    prompt,
                    rawResponse,
                    e.getMessage(),
                    "KEEP_PRE_EDIT_DRAFTS"
            );
        } catch (Exception e) {
            log.warn("AI story final edit failed: generationId={}, error={}", generationId, e.getMessage(), e);
        }
        return drafts;
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

    private StoryDraftPolicy draftPolicy() {
        return new StoryDraftPolicy(properties.getSectionCount(), properties.getTargetCharCount());
    }
}
