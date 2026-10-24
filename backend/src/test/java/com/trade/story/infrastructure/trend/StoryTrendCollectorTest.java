package com.trade.story.infrastructure.trend;

import com.trade.story.infrastructure.config.AiStoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoryTrendCollectorTest {
    @Test
    void sourceFailureRetainsLocalFallbackAndReportsDegradationWithoutNetwork() {
        AiStoryProperties properties = new AiStoryProperties();
        properties.setTrendSourceUrls(List.of("not a valid URI"));
        var context = new StoryTrendCollector(properties).collect();
        assertTrue(context.isDegraded());
        assertTrue(context.getSources().contains("fallback-hot-topics"));
        assertFalse(context.getTrendText().isBlank());
    }

    @Test
    void explicitlyDisabledFetchingIsANormalLocalFallback() {
        AiStoryProperties properties = new AiStoryProperties();
        properties.setTrendFetchEnabled(false);
        var context = new StoryTrendCollector(properties).collect();
        assertFalse(context.isDegraded());
        assertTrue(context.getSources().contains("fallback-hot-topics"));
    }
}
