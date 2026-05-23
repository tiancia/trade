package com.trade.story.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class StoryTopicPlan {
    private String title;
    private String genre;
    private String hotTopic;
    private String targetAudience;
    private String premise;
    private String corePromise;
    private List<String> sellingPoints = new ArrayList<>();
    private List<String> characterBible = new ArrayList<>();
    private List<String> relationshipMap = new ArrayList<>();
    private List<String> worldRules = new ArrayList<>();
    private List<String> plotThreads = new ArrayList<>();
    private List<String> outline = new ArrayList<>();
    private List<StorySectionPlan> sectionPlans = new ArrayList<>();
    private List<String> antiClicheRules = new ArrayList<>();
    private String styleGuide;
    private String rawResponse;
}
