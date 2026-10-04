package com.trade.x.domain.model;

/** AI output; the draft aggregate applies the configured body bounds. */
public record GeneratedXPost(String body, String reviewNote) {
    public GeneratedXPost {
        if (body == null || body.isBlank() || reviewNote == null || reviewNote.isBlank()
                || reviewNote.length() > 2000 || XContentPolicy.hasInvalidCharacters(reviewNote)) {
            throw new IllegalArgumentException("X generation requires a body and bounded review note");
        }
        reviewNote = reviewNote.strip();
    }
}
