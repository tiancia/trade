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
