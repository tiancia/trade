package com.trade.story.scheduler;

import com.trade.story.application.AiStoryService;
import com.trade.story.model.StoryGenerationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Scheduler-facing adapter for one AI story generation run.
 *
 * <p>The heavy workflow lives in {@link AiStoryService}; this class keeps the
 * scheduled loop thin and records concise start/finish logs.</p>
 */
@Component
public class AiStoryScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiStoryScheduler.class);

    private final AiStoryService service;

    public AiStoryScheduler(AiStoryService service) {
        this.service = service;
    }

    public void runScheduledGeneration() {
        long startedAt = System.currentTimeMillis();
        log.info("AI story scheduled trigger fired");
        Optional<StoryGenerationResult> result = service.generateStory();
        log.info(
                "AI story scheduled trigger finished: ran={}, title={}, outputPath={}, elapsedMs={}",
                result.isPresent(),
                result.map(StoryGenerationResult::getTitle).orElse(null),
                result.map(StoryGenerationResult::getOutputPath).orElse(null),
                System.currentTimeMillis() - startedAt
        );
    }
}
