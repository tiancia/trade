package com.trade.story.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class StoryTrendContext {
    private Instant collectedAt;
    private List<String> sources = new ArrayList<>();
    private String trendText;
}
