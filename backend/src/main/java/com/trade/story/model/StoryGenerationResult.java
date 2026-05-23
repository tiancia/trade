package com.trade.story.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.nio.file.Path;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class StoryGenerationResult {
    private String generationId;
    private String title;
    private String hotTopic;
    private Path outputPath;
    private Instant generatedAt;
    private int sectionCount;
    private int targetCharCount;
    private int actualCharCount;
}
