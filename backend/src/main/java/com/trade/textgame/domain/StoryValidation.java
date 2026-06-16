package com.trade.textgame.domain;

import java.util.List;

public final class StoryValidation {
    private StoryValidation() {
    }

    public record Issue(String severity, String code, String path, String message) {
    }

    public record Result(boolean valid, List<Issue> errors, List<Issue> warnings) {
        public static Result of(List<Issue> errors, List<Issue> warnings) {
            return new Result(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
        }
    }
}
