package com.trade.dto.ai;

import lombok.Data;

import java.util.List;

@Data
public class GeminiGenerateResp {
    private List<Candidate> candidates;
    private PromptFeedback promptFeedback;
    private UsageMetadata usageMetadata;

    @Data
    public static class Candidate {
        private GeminiContent content;
        private String finishReason;
        private Integer index;
        private List<SafetyRating> safetyRatings;
    }

    @Data
    public static class PromptFeedback {
        private String blockReason;
        private List<SafetyRating> safetyRatings;
    }

    @Data
    public static class SafetyRating {
        private String category;
        private String probability;
        private Boolean blocked;
    }

    @Data
    public static class UsageMetadata {
        private Integer promptTokenCount;
        private Integer candidatesTokenCount;
        private Integer totalTokenCount;
    }
}
