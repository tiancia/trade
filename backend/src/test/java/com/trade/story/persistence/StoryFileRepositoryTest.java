package com.trade.story.persistence;

import com.trade.story.config.AiStoryProperties;
import com.trade.story.model.StorySectionDraft;
import com.trade.story.model.StoryTopicPlan;
import com.trade.story.model.StoryTrendContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryFileRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void savesStoryAsUtf8TxtAndListsRecentNames() throws Exception {
        AiStoryProperties properties = new AiStoryProperties();
        properties.setOutputDir(tempDir.toString());
        StoryFileRepository repository = new StoryFileRepository(properties);

        Path saved = repository.save(
                new StoryTopicPlan()
                        .setTitle("雨夜规则")
                        .setGenre("规则怪谈")
                        .setHotTopic("城市规则怪谈"),
                List.of(new StorySectionDraft()
                        .setSection(1)
                        .setSectionTitle("雨线")
                        .setContent("雨从晚上八点开始下。林舟发现门口多了一张纸。")),
                new StoryTrendContext()
                        .setCollectedAt(Instant.now())
                        .setSources(List.of("fallback-hot-topics"))
                        .setTrendText("规则怪谈"),
                24
        );

        assertTrue(Files.exists(saved));
        assertTrue(saved.getFileName().toString().endsWith(".txt"));
        String content = Files.readString(saved, StandardCharsets.UTF_8);
        assertTrue(content.contains("标题：雨夜规则"));
        assertTrue(content.contains("第1节 雨线"));
        assertTrue(content.contains("雨从晚上八点开始下"));
        assertEquals(List.of(saved.getFileName().toString()), repository.recentStoryNames());
    }
}
